import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.avm2.instructions.InstructionDefinition;
import com.jpexs.decompiler.flash.tags.Tag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Development probe: replace the instruction that produces one addGun argument
 * with a freshly built instruction that may have a different encoded length,
 * then re-parse the output and diff every resolved argument of every weapon.
 *
 * Usage: ReplaceProbe <in.swf> <out.swf> <weaponId> <argIndex> <value> <instructionName>
 */
public final class ReplaceProbe {

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String weapon = args[2];
        int argIndex = Integer.parseInt(args[3]);
        double doubleValue = Double.parseDouble(args[4]);
        String insName = args[5];

        SWF swf;
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(in.toFile()))) {
            swf = new SWF(stream, false);
        }
        WeaponRegistry.Resolution before = WeaponRegistry.resolve(swf);
        System.out.println("BEFORE resolved=" + before.byId.size() + " problems=" + before.problems.size());

        WeaponRegistry.Registration reg = before.byId.get(weapon);
        if (reg == null) {
            throw new IllegalStateException("weapon not found: " + weapon);
        }
        int pos = reg.arg(argIndex).producer;
        AVM2Instruction old = before.code.code.get(pos);
        System.out.println("OLD pos=" + pos + " name=" + old.definition.instructionName
                + " len=" + old.getBytesLength()
                + " ops=" + Arrays.toString(old.operands)
                + " addr=0x" + Long.toHexString(old.getAddress()));

        AVM2Instruction replacement;
        if ("auto".equals(insName)) {
            Avm2ConstantEditor.Encoded encoded = Avm2ConstantEditor.encode(before.abc, old, doubleValue);
            replacement = encoded.instruction;
            System.out.println("NEW " + encoded.describe());
        } else {
            InstructionDefinition definition = definition(insName);
            replacement = new AVM2Instruction(old.getAddress(), definition, new int[]{(int) doubleValue});
            System.out.println("NEW name=" + replacement.definition.instructionName
                    + " len=" + replacement.getBytesLength()
                    + " ops=" + Arrays.toString(replacement.operands));
        }

        int codeSizeBefore = before.code.code.size();
        long endBefore = before.code.getEndOffset();
        before.code.replaceInstruction(pos, replacement, before.body);
        before.body.setModified();
        ((Tag) before.abc.parentTag).setModified(true);
        System.out.println("CODE size " + codeSizeBefore + " -> " + before.code.code.size()
                + "  endOffset " + endBefore + " -> " + before.code.getEndOffset());

        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(out.toFile()))) {
            swf.saveTo(stream);
        }
        System.out.println("WROTE " + out);

        SWF reread;
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(out.toFile()))) {
            reread = new SWF(stream, false);
        }
        WeaponRegistry.Resolution after = WeaponRegistry.resolve(reread);
        System.out.println("AFTER resolved=" + after.byId.size() + " problems=" + after.problems.size());

        int diffs = 0;
        for (Map.Entry<String, WeaponRegistry.Registration> entry : before.byId.entrySet()) {
            WeaponRegistry.Registration a = entry.getValue();
            WeaponRegistry.Registration b = after.byId.get(entry.getKey());
            if (b == null) {
                System.out.println("MISSING after: " + entry.getKey());
                diffs++;
                continue;
            }
            for (int i = 1; i <= WeaponRegistry.ARG_COUNT; i++) {
                String da = a.arg(i) == null ? "<null>" : a.arg(i).display();
                String db = b.arg(i) == null ? "<null>" : b.arg(i).display();
                if (!da.equals(db)) {
                    System.out.println("DIFF " + entry.getKey() + " arg" + i + ": " + da + " -> " + db);
                    diffs++;
                }
            }
        }
        System.out.println("TOTAL_DIFFS=" + diffs);
        System.out.println("TARGET_NOW=" + after.byId.get(weapon).arg(argIndex).display());
        System.out.println("PROBLEMS_AFTER=" + after.problems);
    }

    private static InstructionDefinition definition(String name) {
        for (InstructionDefinition definition : AVM2Code.instructionSet) {
            if (definition != null && name.equals(definition.instructionName)) {
                return definition;
            }
        }
        throw new IllegalArgumentException("unknown instruction: " + name);
    }
}
