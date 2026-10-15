import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.AVM2ConstantPool;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.avm2.instructions.InstructionDefinition;

/**
 * Builds the AVM2 instruction that pushes a configured numeric value, choosing the narrowest
 * instruction form that can represent it and allocating a constant-pool entry only when the value
 * cannot be encoded as an inline operand.
 *
 * <p>Two invariants are enforced here:</p>
 * <ul>
 *   <li>No existing constant-pool entry is ever overwritten. {@code addInt}/{@code addUInt}/{@code
 *       addDouble} either append a new entry or return the index of an entry that already holds
 *       exactly this value, so an entry shared by other weapons keeps its meaning. {@code setInt}
 *       and {@code setDouble} are deliberately never called.</li>
 *   <li>The original instruction is replaced as a whole object, never byte-patched. FFDec then
 *       recalculates instruction addresses, branch targets and method metadata from the new
 *       instruction lengths.</li>
 * </ul>
 */
public final class Avm2ConstantEditor {

    /**
     * Largest value this project is willing to put in a {@code pushshort} operand.
     *
     * <p>Although the AVM2 specification describes {@code pushshort} as a signed 24 bit value, FFDec
     * only round-trips 16 bits through it: writing {@code pushshort 100000} and reading the result
     * back yields {@code -31072}. Values above {@link #PUSH_SHORT_MAX} are therefore routed through
     * the constant pool instead.</p>
     *
     * <p>Negative values never use {@code pushshort} either. FFDec writes the operand as a u30 of the
     * 32 bit two's complement value, while the specification defines it as a u30 of the 24 bit two's
     * complement value, so {@code -1} would occupy five bytes instead of four and a real AVM2 would
     * decode a different instruction stream. Negative values use {@code pushint}.</p>
     */
    private static final long PUSH_SHORT_MAX = 32767L;
    private static final long UINT_MAX = 4294967295L;

    /** The outcome of encoding one value. */
    public static final class Encoded {
        public final AVM2Instruction instruction;
        public final String form;
        /** {@code "none"} for an inline operand, otherwise {@code "int"}, {@code "uint"} or {@code "double"}. */
        public final String poolKind;
        public final int poolIndex;
        public final boolean appendedPoolEntry;
        public final double value;
        public final int bytesBefore;
        public final int bytesAfter;

        Encoded(AVM2Instruction instruction, String form, String poolKind, int poolIndex,
                boolean appendedPoolEntry, double value, int bytesBefore, int bytesAfter) {
            this.instruction = instruction;
            this.form = form;
            this.poolKind = poolKind;
            this.poolIndex = poolIndex;
            this.appendedPoolEntry = appendedPoolEntry;
            this.value = value;
            this.bytesBefore = bytesBefore;
            this.bytesAfter = bytesAfter;
        }

        public String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append(form).append(' ').append(format(value));
            if (poolIndex >= 0) {
                builder.append(" (").append(poolKind).append(" #").append(poolIndex);
                builder.append(appendedPoolEntry ? ", appended" : ", reused existing");
                builder.append(')');
            }
            builder.append(" [").append(bytesBefore).append(" -> ").append(bytesAfter).append(" bytes]");
            return builder.toString();
        }
    }

    private Avm2ConstantEditor() {
    }

    public static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) <= 1.0e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * Encodes {@code value} into a brand new instruction that can be substituted for {@code original}.
     *
     * @param abc      the ABC owning the target method, used for constant-pool allocation
     * @param original the instruction being replaced (only its address and length are read)
     * @param value    the configured value; must be finite
     */
    public static Encoded encode(ABC abc, AVM2Instruction original, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("cannot encode a non-finite value");
        }
        AVM2ConstantPool pool = abc.constants;
        int bytesBefore = original.getBytesLength();
        long address = original.getAddress();
        boolean integral = value == Math.rint(value);

        if (integral) {
            long asLong = (long) value;
            if (asLong >= -128L && asLong <= 127L) {
                return inline(address, bytesBefore, "pushbyte", (int) asLong, value, "none", -1, false);
            }
            if (asLong > 127L && asLong <= PUSH_SHORT_MAX) {
                return inline(address, bytesBefore, "pushshort", (int) asLong, value, "none", -1, false);
            }
            if (asLong >= 0 && asLong <= UINT_MAX) {
                int before = pool.getUIntCount();
                int index = pool.addUInt(asLong);
                return pooled(address, bytesBefore, "pushuint", index, value, "uint", index >= before);
            }
            if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                int before = pool.getIntCount();
                int index = pool.addInt((int) asLong);
                return pooled(address, bytesBefore, "pushint", index, value, "int", index >= before);
            }
        }

        int before = pool.getDoubleCount();
        int index = pool.addDouble(value);
        return pooled(address, bytesBefore, "pushdouble", index, value, "double", index >= before);
    }

    private static Encoded inline(long address, int bytesBefore, String form, int operand, double value,
                                  String poolKind, int poolIndex, boolean appended) {
        InstructionDefinition definition = definition(form);
        AVM2Instruction instruction = new AVM2Instruction(address, definition, new int[]{operand});
        return new Encoded(instruction, form, poolKind, poolIndex, appended, value, bytesBefore,
                instruction.getBytesLength());
    }

    private static Encoded pooled(long address, int bytesBefore, String form, int poolIndex, double value,
                                  String poolKind, boolean appended) {
        InstructionDefinition definition = definition(form);
        AVM2Instruction instruction = new AVM2Instruction(address, definition, new int[]{poolIndex});
        return new Encoded(instruction, form, poolKind, poolIndex, appended, value, bytesBefore,
                instruction.getBytesLength());
    }

    public static InstructionDefinition definition(String name) {
        for (InstructionDefinition definition : AVM2Code.instructionSet) {
            if (definition != null && name.equals(definition.instructionName)) {
                return definition;
            }
        }
        throw new IllegalStateException("FFDec has no instruction definition named '" + name + "'");
    }

    /**
     * Reads the value an instruction pushes, for the numeric push forms this project supports.
     * Returns {@code null} when the instruction is not a plain numeric constant.
     */
    public static Double readConstant(AVM2ConstantPool pool, AVM2Instruction instruction) {
        String name = instruction.definition.instructionName;
        int[] operands = instruction.operands;
        switch (name) {
            case "pushbyte":
            case "pushshort":
                return operands == null || operands.length == 0 ? null : (double) operands[0];
            case "pushint":
                return operands == null || operands.length == 0 ? null : (double) pool.getInt(operands[0]);
            case "pushuint":
                return operands == null || operands.length == 0 ? null : (double) pool.getUInt(operands[0]);
            case "pushdouble":
                return operands == null || operands.length == 0 ? null : pool.getDouble(operands[0]);
            default:
                return null;
        }
    }

    /** True for the instruction families that can be used to carry a mutable numeric attribute. */
    public static boolean isNumericPush(AVM2Instruction instruction) {
        if (instruction == null) {
            return false;
        }
        switch (instruction.definition.instructionName) {
            case "pushbyte":
            case "pushshort":
            case "pushint":
            case "pushuint":
            case "pushdouble":
                return true;
            default:
                return false;
        }
    }
}
