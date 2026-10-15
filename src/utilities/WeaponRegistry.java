import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.ScriptPack;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.AVM2ConstantPool;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.types.MethodBody;
import com.jpexs.decompiler.flash.abc.types.Multiname;
import com.jpexs.decompiler.flash.abc.types.traits.Trait;
import com.jpexs.decompiler.flash.abc.types.traits.TraitMethodGetterSetter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Locates {@code Stats_Guns.Init()} {@code addGun(...)} registrations and resolves every one of the
 * 33 call arguments to the AVM2 instruction that produced it.
 *
 * <p>Statistical searching (a fixed number of pushbyte instructions, a fixed window around the
 * weapon name) is deliberately not used. {@code Stats_Guns.Init} is heavily obfuscated: it contains
 * unreachable blocks that are jumped over, plus opaque predicates of the form
 * {@code pushfalse; iffalse L; <dead>; L:}. This class therefore performs an abstract interpretation
 * of the AVM2 operand stack over the reachable control-flow graph, pruning constant conditional
 * branches, and reads the argument list directly off the operand stack at the call site.</p>
 *
 * <p>The parameter-to-field mapping was confirmed from the decompiled {@code addGun} body
 * (decompiled/scripts/Stats_Guns.as:1522-1574), not guessed from parameter names.</p>
 */
public final class WeaponRegistry {

    public static final String CLASS_PATH = "Stats_Guns";
    public static final String METHOD_NAME = "Init";
    public static final String ADD_GUN = "addGun";
    /** addGun has 33 declared parameters. */
    public static final int ARG_COUNT = 33;

    /** Statically known shape of one operand-stack slot. */
    public enum Kind {
        INT, DOUBLE, STRING, BOOL, NULL, ARRAY, OBJECT, OTHER
    }

    /** One value on the simulated operand stack. */
    public static final class Slot {
        public Kind kind = Kind.OTHER;
        public boolean constant;
        public long intValue;
        public double doubleValue;
        public String stringValue;
        public boolean boolValue;
        public int arrayLength = -1;
        /** Index into {@link AVM2Code#code} of the instruction that produced this value. */
        public int producer = -1;

        Slot copy() {
            Slot s = new Slot();
            s.kind = kind;
            s.constant = constant;
            s.intValue = intValue;
            s.doubleValue = doubleValue;
            s.stringValue = stringValue;
            s.boolValue = boolValue;
            s.arrayLength = arrayLength;
            s.producer = producer;
            return s;
        }

        /** Numeric view; NaN when the slot is not statically numeric. */
        public double numeric() {
            if (!constant) {
                return Double.NaN;
            }
            switch (kind) {
                case INT:
                    return intValue;
                case DOUBLE:
                    return doubleValue;
                default:
                    return Double.NaN;
            }
        }

        public boolean isIntegral() {
            if (!constant) {
                return false;
            }
            if (kind == Kind.INT) {
                return true;
            }
            return kind == Kind.DOUBLE && doubleValue == Math.rint(doubleValue)
                    && !Double.isInfinite(doubleValue);
        }

        public String display() {
            if (!constant) {
                return "<" + kind + ">";
            }
            switch (kind) {
                case INT:
                    return Long.toString(intValue);
                case DOUBLE:
                    return Double.toString(doubleValue);
                case STRING:
                    return "\"" + stringValue + "\"";
                case BOOL:
                    return Boolean.toString(boolValue);
                case NULL:
                    return "null";
                case ARRAY:
                    return "Array[" + arrayLength + "]";
                case OBJECT:
                    return "Object";
                default:
                    return "<" + kind + ">";
            }
        }
    }

    /** One resolved {@code addGun(...)} call. */
    public static final class Registration {
        /** Index into {@link AVM2Code#code} of the {@code callpropvoid addGun} instruction. */
        public final int callIndex;
        public final long callAddress;
        /** Argument slots, {@code args[i]} is addGun parameter {@code i+1}. */
        public final Slot[] args;
        /** Non-null when this call could not be resolved reliably; such a call is never patched. */
        public final String problem;

        Registration(int callIndex, long callAddress, Slot[] args, String problem) {
            this.callIndex = callIndex;
            this.callAddress = callAddress;
            this.args = args;
            this.problem = problem;
        }

        public boolean resolved() {
            return problem == null;
        }

        public String weaponId() {
            Slot s = args[1];
            return s != null && s.kind == Kind.STRING ? s.stringValue : null;
        }

        public Slot arg(int oneBased) {
            return args[oneBased - 1];
        }
    }

    /** Complete resolution result for one {@code Stats_Guns.Init} body. */
    public static final class Resolution {
        public final ABC abc;
        public final MethodBody body;
        public final AVM2Code code;
        public final int bodyIndex;
        /** Weapon id to registration, insertion ordered (registration order in the bytecode). */
        public final Map<String, Registration> byId = new LinkedHashMap<>();
        /** Every addGun call site found, including unresolved ones. */
        public final List<Registration> all = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();

        Resolution(ABC abc, MethodBody body, int bodyIndex) {
            this.abc = abc;
            this.body = body;
            this.bodyIndex = bodyIndex;
            this.code = body.getCode();
        }
    }

    private WeaponRegistry() {
    }

    // ------------------------------------------------------------------ lookup

    /** Finds the single ABC declaring {@code classPath}. */
    public static ABC findAbc(SWF swf, String classPath) {
        List<ScriptPack> packs = new ArrayList<>();
        for (ScriptPack pack : swf.getAS3Packs()) {
            if (classPath.equals(pack.getPathScriptName())) {
                packs.add(pack);
            }
        }
        if (packs.size() != 1) {
            throw new IllegalStateException("Expected exactly one class named " + classPath
                    + " but found " + packs.size());
        }
        return packs.get(0).abc;
    }

    /** Finds the body index of a static method by exact name; refuses zero or multiple matches. */
    public static int findStaticMethodBody(ABC abc, String className, String methodName) {
        int classIndex = abc.findClassByName(className);
        if (classIndex < 0) {
            throw new IllegalStateException("Class definition not found: " + className);
        }
        int bodyIndex = -1;
        int named = 0;
        for (Trait trait : abc.class_info.get(classIndex).static_traits.traits) {
            if (!(trait instanceof TraitMethodGetterSetter methodTrait)) {
                continue;
            }
            Multiname name = abc.constants.getMultiname(trait.name_index);
            if (name.hasOwnName() && methodName.equals(abc.constants.getString(name.name_index))) {
                named++;
                bodyIndex = abc.findBodyIndex(methodTrait.method_info);
            }
        }
        if (named != 1) {
            throw new IllegalStateException("Expected exactly one static method " + className + "." + methodName
                    + " but found " + named);
        }
        if (bodyIndex < 0 || bodyIndex >= abc.bodies.size()) {
            throw new IllegalStateException("Method body not found: " + className + "." + methodName);
        }
        return bodyIndex;
    }

    // ------------------------------------------------------------------ resolve

    public static Resolution resolve(SWF swf) {
        ABC abc = findAbc(swf, CLASS_PATH);
        return resolve(abc, findStaticMethodBody(abc, CLASS_PATH, METHOD_NAME));
    }

    public static Resolution resolve(ABC abc, int bodyIndex) {
        Resolution res = new Resolution(abc, abc.bodies.get(bodyIndex), bodyIndex);
        AVM2Code code = res.code;
        int size = code.code.size();

        List<ArrayList<Slot>> inStacks = simulate(abc, res.body, code);

        for (int i = 0; i < size; i++) {
            AVM2Instruction ins = code.code.get(i);
            if (!"callpropvoid".equals(ins.definition.instructionName)) {
                continue;
            }
            int[] ops = ins.operands == null ? new int[0] : ins.operands;
            if (ops.length < 2 || !ADD_GUN.equals(multinameName(abc.constants, ins))) {
                continue;
            }
            int argc = ops[1];
            ArrayList<Slot> stack = inStacks.get(i);
            String problem = null;
            Slot[] args = null;
            if (argc != ARG_COUNT) {
                problem = "call property argument count " + argc + " != " + ARG_COUNT;
            } else if (stack == null) {
                problem = "operand stack unknown at call site";
            } else if (stack.size() != ARG_COUNT + 1) {
                problem = "operand stack depth " + stack.size() + " != " + (ARG_COUNT + 1)
                        + " (receiver + " + ARG_COUNT + " arguments)";
            } else {
                args = new Slot[ARG_COUNT];
                for (int k = 0; k < ARG_COUNT; k++) {
                    args[k] = stack.get(k + 1);
                    if (args[k].producer < 0) {
                        problem = "argument " + (k + 1) + " has no known producer";
                        break;
                    }
                }
            }
            Registration reg = new Registration(i, ins.getAddress(),
                    args == null ? emptyArgs() : args, problem);
            res.all.add(reg);
            if (reg.resolved()) {
                String id = reg.weaponId();
                if (id == null || id.isEmpty()) {
                    res.problems.add("call site at 0x" + Long.toHexString(ins.getAddress())
                            + " has a non-constant weapon id");
                    continue;
                }
                if (res.byId.containsKey(id)) {
                    res.problems.add("duplicate registration for weapon id '" + id + "'");
                    continue;
                }
                res.byId.put(id, reg);
            } else {
                res.problems.add("unresolved addGun call at 0x" + Long.toHexString(ins.getAddress())
                        + ": " + problem);
            }
        }
        return res;
    }

    private static Slot[] emptyArgs() {
        Slot[] args = new Slot[ARG_COUNT];
        for (int i = 0; i < ARG_COUNT; i++) {
            args[i] = new Slot();
        }
        return args;
    }

    public static String multinameName(AVM2ConstantPool constants, AVM2Instruction instruction) {
        int[] ops = instruction.operands == null ? new int[0] : instruction.operands;
        if (ops.length == 0) {
            return "";
        }
        Multiname multiname = constants.getMultiname(ops[0]);
        if (multiname == null || !multiname.hasOwnName()) {
            return "";
        }
        return constants.getString(multiname.name_index);
    }

    // ------------------------------------------------------------------ abstract interpretation

    /**
     * Forward abstract interpretation over the reachable CFG. Conditional branches whose condition
     * is a statically known boolean are pruned to the taken edge, which removes the obfuscator's
     * dead blocks. Returns the operand stack on entry to every instruction (absent = unreachable).
     */
    static List<ArrayList<Slot>> simulate(ABC abc, MethodBody body, AVM2Code code) {
        int size = code.code.size();
        List<ArrayList<Slot>> inStacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            inStacks.add(null);
        }
        if (size == 0) {
            return inStacks;
        }
        Set<Integer> onWorklist = new HashSet<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        inStacks.set(0, new ArrayList<>());
        work.add(0);
        onWorklist.add(0);

        while (!work.isEmpty()) {
            int i = work.poll();
            onWorklist.remove(i);
            ArrayList<Slot> in = new ArrayList<>(inStacks.get(i));
            AVM2Instruction ins = code.code.get(i);
            String name = ins.definition.instructionName;
            int[] ops = ins.operands == null ? new int[0] : ins.operands;

            ArrayList<Slot> out = new ArrayList<>(in);
            boolean isExit = false;
            int target = -1;

            switch (name) {
                case "pushbyte":
                case "pushshort":
                    pushConst(out, Kind.INT, ops.length > 0 ? ops[0] : 0, i);
                    break;
                case "pushint":
                    pushConst(out, Kind.INT, ops.length > 0 ? abc.constants.getInt(ops[0]) : 0, i);
                    break;
                case "pushuint":
                    pushConst(out, Kind.INT, ops.length > 0 ? abc.constants.getUInt(ops[0]) : 0, i);
                    break;
                case "pushdouble":
                    pushConst(out, Kind.DOUBLE, ops.length > 0 ? abc.constants.getDouble(ops[0]) : 0.0, i);
                    break;
                case "pushstring":
                    pushString(out, ops.length > 0 ? abc.constants.getString(ops[0]) : "", i);
                    break;
                case "pushtrue":
                    pushBool(out, true, i);
                    break;
                case "pushfalse":
                    pushBool(out, false, i);
                    break;
                case "pushnull":
                case "pushundefined": {
                    Slot s = new Slot();
                    s.kind = Kind.NULL;
                    s.constant = true;
                    s.producer = i;
                    out.add(s);
                    break;
                }
                case "dup": {
                    if (out.isEmpty()) {
                        abort(out);
                    } else {
                        out.add(out.get(out.size() - 1).copy());
                    }
                    break;
                }
                case "getlex":
                case "findpropstrict":
                case "findproperty":
                case "newclass":
                case "newfunction":
                case "getlocal0":
                case "getlocal1":
                case "getlocal2":
                case "getlocal3":
                case "getlocal": {
                    Slot s = new Slot();
                    s.kind = Kind.OTHER;
                    s.producer = i;
                    out.add(s);
                    break;
                }
                case "newarray": {
                    int n = ops.length > 0 ? ops[0] : 0;
                    if (!pop(out, n)) {
                        abort(out);
                    } else {
                        Slot s = new Slot();
                        s.kind = Kind.ARRAY;
                        s.arrayLength = n;
                        s.constant = true;
                        s.producer = i;
                        out.add(s);
                    }
                    break;
                }
                case "newobject": {
                    // The operand is the number of name/value pairs, so it pops twice that many values.
                    int n = ops.length > 0 ? ops[0] : 0;
                    if (!pop(out, n * 2)) {
                        abort(out);
                    } else {
                        Slot s = new Slot();
                        s.kind = Kind.OBJECT;
                        s.constant = true;
                        s.producer = i;
                        out.add(s);
                    }
                    break;
                }
                case "callpropvoid": {
                    int argc = ops.length > 1 ? ops[1] : 0;
                    if (!pop(out, argc + 1)) {
                        abort(out);
                    }
                    break;
                }
                case "callproplex":
                case "callproperty":
                case "callsuper": {
                    int argc = ops.length > 1 ? ops[1] : 0;
                    if (!pop(out, argc + 1)) {
                        abort(out);
                    } else {
                        out.add(opaque(i));
                    }
                    break;
                }
                case "callstatic":
                case "call": {
                    int argc = ops.length > 0 ? ops[0] : 0;
                    if (!pop(out, argc)) {
                        abort(out);
                    } else {
                        out.add(opaque(i));
                    }
                    break;
                }
                case "callsupervoid": {
                    int argc = ops.length > 1 ? ops[1] : 0;
                    if (!pop(out, argc + 1)) {
                        abort(out);
                    }
                    break;
                }
                case "callstaticvoid": {
                    int argc = ops.length > 0 ? ops[0] : 0;
                    if (!pop(out, argc)) {
                        abort(out);
                    }
                    break;
                }
                case "construct":
                case "constructsuper":
                case "constructprop": {
                    int argc = name.equals("constructprop")
                            ? (ops.length > 1 ? ops[1] : 0) + 1
                            : (ops.length > 0 ? ops[0] : 0);
                    if (!pop(out, argc)) {
                        abort(out);
                    } else {
                        out.add(opaque(i));
                    }
                    break;
                }
                case "setproperty":
                case "initproperty": {
                    if (!pop(out, 2)) {
                        abort(out);
                    }
                    break;
                }
                case "coerce":
                case "coerce_a":
                case "setlocal":
                case "setlocal0":
                case "setlocal1":
                case "setlocal2":
                case "setlocal3": {
                    if (!pop(out, 1)) {
                        abort(out);
                    }
                    break;
                }
                case "getproperty":
                case "getdescendants":
                case "convert_u":
                case "convert_i":
                case "convert_d":
                case "convert_b":
                case "convert_s":
                case "convert_o":
                case "negate":
                case "not":
                case "bitnot":
                case "increment":
                case "decrement":
                case "increment_i":
                case "decrement_i":
                case "typeof":
                case "astype":
                case "astypelate":
                case "esc_xelem":
                case "esc_xattr": {
                    if (!pop(out, 1)) {
                        abort(out);
                    } else {
                        out.add(opaque(i));
                    }
                    break;
                }
                case "pushscope":
                case "pop":
                case "throw":
                case "returnvalue":
                case "kill": {
                    if (!pop(out, 1)) {
                        abort(out);
                    }
                    if (!"kill".equals(name) && !"pop".equals(name) && !"pushscope".equals(name)) {
                        isExit = "throw".equals(name) || "returnvalue".equals(name);
                    }
                    break;
                }
                case "returnvoid":
                    isExit = true;
                    break;
                case "iffalse":
                case "iftrue": {
                    Boolean cond = popBool(out);
                    target = targetPos(code, ins);
                    boolean fallThrough;
                    if (cond == null) {
                        fallThrough = true;
                    } else {
                        boolean taken = "iffalse".equals(name) ? !cond : cond;
                        fallThrough = !taken;
                        if (!taken) {
                            target = -1;
                        }
                    }
                    if (fallThrough) {
                        enqueue(inStacks, work, onWorklist, i + 1, out);
                    }
                    if (target >= 0) {
                        enqueue(inStacks, work, onWorklist, target, out);
                    }
                    continue;
                }
                case "ifeq":
                case "ifne":
                case "iflt":
                case "ifle":
                case "ifgt":
                case "ifge":
                case "ifnlt":
                case "ifnle":
                case "ifngt":
                case "ifnge":
                case "ifstricteq":
                case "ifstrictne":
                case "ifnstricteq":
                case "ifnstrictne": {
                    if (!pop(out, 2)) {
                        abort(out);
                    } else {
                        target = targetPos(code, ins);
                        enqueue(inStacks, work, onWorklist, i + 1, out);
                        enqueue(inStacks, work, onWorklist, target, new ArrayList<>(out));
                        continue;
                    }
                    break;
                }
                case "jump": {
                    target = targetPos(code, ins);
                    enqueue(inStacks, work, onWorklist, target, out);
                    continue;
                }
                case "lookupswitch": {
                    if (!pop(out, 1)) {
                        abort(out);
                    } else {
                        enqueue(inStacks, work, onWorklist, i + 1, out);
                        for (int c : ops) {
                            enqueue(inStacks, work, onWorklist, c, new ArrayList<>(out));
                        }
                    }
                    continue;
                }
                case "label":
                case "nop":
                case "debugline":
                case "debug":
                case "debugfile":
                case "bkpt":
                    break;
                case "swap": {
                    if (out.size() < 2) {
                        abort(out);
                    } else {
                        Slot a = out.get(out.size() - 1);
                        Slot b = out.get(out.size() - 2);
                        out.set(out.size() - 1, b);
                        out.set(out.size() - 2, a);
                    }
                    break;
                }
                default: {
                    // Unknown opcode: taint the whole stack so no conclusion is drawn from it.
                    abort(out);
                    break;
                }
            }

            if (!isExit) {
                enqueue(inStacks, work, onWorklist, i + 1, out);
            }
        }
        return inStacks;
    }

    private static void enqueue(List<ArrayList<Slot>> inStacks, ArrayDeque<Integer> work,
            Set<Integer> onWorklist, int index, ArrayList<Slot> stack) {
        if (index < 0 || index >= inStacks.size()) {
            return;
        }
        if (stack.contains(null)) {
            return;
        }
        ArrayList<Slot> existing = inStacks.get(index);
        if (existing == null) {
            inStacks.set(index, new ArrayList<>(stack));
            if (onWorklist.add(index)) {
                work.add(index);
            }
            return;
        }
        if (existing.size() != stack.size()) {
            // Inconsistent stack depth: never trust this program point.
            for (Slot slot : existing) {
                slot.constant = false;
                slot.kind = Kind.OTHER;
                slot.producer = -1;
            }
            return;
        }
        // Merge: values that differ become unknown.
        for (int i = 0; i < stack.size(); i++) {
            Slot a = existing.get(i);
            Slot b = stack.get(i);
            if (!sameSlot(a, b)) {
                a.constant = false;
                a.kind = Kind.OTHER;
                a.producer = -1;
            }
        }
    }

    private static boolean sameSlot(Slot a, Slot b) {
        if (a.constant != b.constant || a.kind != b.kind) {
            return false;
        }
        if (!a.constant) {
            return true;
        }
        switch (a.kind) {
            case INT:
                return a.intValue == b.intValue;
            case DOUBLE:
                return Double.compare(a.doubleValue, b.doubleValue) == 0;
            case STRING:
                return a.stringValue.equals(b.stringValue);
            case BOOL:
                return a.boolValue == b.boolValue;
            case NULL:
                return true;
            case ARRAY:
                return a.arrayLength == b.arrayLength;
            default:
                return false;
        }
    }

    private static Slot opaque(int producer) {
        Slot s = new Slot();
        s.kind = Kind.OTHER;
        s.producer = producer;
        return s;
    }

    private static void abort(ArrayList<Slot> stack) {
        for (int i = 0; i < stack.size(); i++) {
            stack.set(i, opaque(-1));
        }
    }

    private static boolean pop(ArrayList<Slot> stack, int count) {
        if (count == 0) {
            return true;
        }
        if (stack.size() < count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            stack.remove(stack.size() - 1);
        }
        return true;
    }

    private static Boolean popBool(ArrayList<Slot> stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Slot s = stack.remove(stack.size() - 1);
        if (s.constant && s.kind == Kind.BOOL) {
            return s.boolValue;
        }
        return null;
    }

    private static void pushConst(ArrayList<Slot> out, Kind kind, double value, int producer) {
        Slot s = new Slot();
        s.kind = kind;
        s.constant = true;
        s.producer = producer;
        if (kind == Kind.INT) {
            s.intValue = (long) value;
        } else {
            s.doubleValue = value;
        }
        out.add(s);
    }

    private static void pushString(ArrayList<Slot> out, String value, int producer) {
        Slot s = new Slot();
        s.kind = Kind.STRING;
        s.constant = true;
        s.stringValue = value;
        s.producer = producer;
        out.add(s);
    }

    private static void pushBool(ArrayList<Slot> out, boolean value, int producer) {
        Slot s = new Slot();
        s.kind = Kind.BOOL;
        s.constant = true;
        s.boolValue = value;
        s.producer = producer;
        out.add(s);
    }

    static int targetPos(AVM2Code code, AVM2Instruction ins) {
        try {
            return code.adr2pos(ins.getTargetAddress());
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ reporting

    /** Structural signature check for every parameter position of addGun. */
    public static String checkShape(Registration reg) {
        Kind[] expected = new Kind[]{
                Kind.INT, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING,
                Kind.INT, Kind.INT,
                Kind.INT, Kind.INT, Kind.INT, Kind.INT, Kind.INT,
                Kind.INT, Kind.INT, Kind.INT,
                Kind.INT,
                Kind.BOOL,
                Kind.INT,
                Kind.INT, Kind.INT,
                Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING,
                Kind.OTHER, Kind.OTHER, Kind.OTHER,
                Kind.ARRAY, Kind.OBJECT, Kind.STRING
        };
        for (int i = 0; i < ARG_COUNT; i++) {
            Slot s = reg.args[i];
            if (!s.constant) {
                return "parameter " + (i + 1) + " is not a constant";
            }
            Kind want = expected[i];
            if (want == Kind.OTHER) {
                continue;
            }
            if (want == Kind.INT && (s.kind == Kind.INT || (s.kind == Kind.DOUBLE && s.isIntegral()))) {
                continue;
            }
            if (s.kind != want) {
                return "parameter " + (i + 1) + " is " + s.kind + " but addGun expects " + want;
            }
        }
        if (reg.weaponId() == null || reg.weaponId().isEmpty()) {
            return "weapon id is empty";
        }
        return null;
    }

    /** Known-good values from the decompiled source, used as a resolver self-test. */
    public static Map<String, Object[]> expectations() {
        Map<String, Object[]> map = new TreeMap<>();
        map.put("USP", new Object[]{8, 15.0, 13, 12.0, 15, 66.0, 16, 3.0, 18, 0.25});
        map.put("Golden Gun", new Object[]{8, 500.0});
        map.put("M4", new Object[]{8, 10.0, 18, 0.15});
        map.put("Dragunov", new Object[]{8, 90.0});
        return map;
    }

    /** Verifies the resolver against the decompiled ground truth; returns null when consistent. */
    public static String selfTest(Resolution res) {
        for (Map.Entry<String, Object[]> entry : expectations().entrySet()) {
            String id = entry.getKey();
            Object[] flat = entry.getValue();
            Registration reg = res.byId.get(id);
            if (reg == null) {
                return "self-test: weapon '" + id + "' was not resolved";
            }
            for (int i = 0; i < flat.length; i += 2) {
                int arg = (Integer) flat[i];
                double want = (Double) flat[i + 1];
                double got = reg.arg(arg).numeric();
                if (Double.compare(got, want) != 0) {
                    return "self-test: " + id + " parameter " + arg + " = " + got + " but source says " + want;
                }
            }
        }
        return null;
    }
}
