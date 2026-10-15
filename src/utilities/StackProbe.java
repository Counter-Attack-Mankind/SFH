import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/** Development probe: prints the simulated operand-stack depth per instruction. */
public final class StackProbe {
    public static void main(String[] args) throws Exception {
        SWF swf;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(args[0]))) {
            swf = new SWF(in, false);
        }
        ABC abc = WeaponRegistry.findAbc(swf, WeaponRegistry.CLASS_PATH);
        int bodyIndex = WeaponRegistry.findStaticMethodBody(abc, WeaponRegistry.CLASS_PATH,
                WeaponRegistry.METHOD_NAME);
        AVM2Code code = abc.bodies.get(bodyIndex).getCode();
        List<ArrayList<WeaponRegistry.Slot>> stacks = WeaponRegistry.simulate(abc, abc.bodies.get(bodyIndex), code);
        int from = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int to = args.length > 2 ? Integer.parseInt(args[2]) : code.code.size();
        for (int i = from; i < to && i < code.code.size(); i++) {
            AVM2Instruction ins = code.code.get(i);
            ArrayList<WeaponRegistry.Slot> st = stacks.get(i);
            System.out.printf("%6d 0x%04x depth=%s  %-16s%s%n", i, ins.getAddress(),
                    st == null ? "-" : Integer.toString(st.size()), ins.definition.instructionName,
                    st == null ? "" : "  top=" + (st.isEmpty() ? "-" : st.get(st.size() - 1).display()));
        }
    }
}
