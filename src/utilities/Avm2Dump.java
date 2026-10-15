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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only AVM2 bytecode dumper. Used to inspect the true instruction stream of
 * a decompiled class/method pair, because the FFDec decompiler output loses the
 * argument-push structure of complex calls.
 *
 * Usage: Avm2Dump &lt;swf&gt; &lt;class&gt; &lt;method&gt; [fromIndex] [count]
 */
public final class Avm2Dump {
    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.err.println("Usage: Avm2Dump <swf> <class> <method> [fromIndex] [count]");
                System.exit(2);
            }
            String swfPath = args[0];
            String className = args[1];
            String methodName = args[2];
            int from = args.length > 3 ? Integer.parseInt(args[3]) : 0;
            int count = args.length > 4 ? Integer.parseInt(args[4]) : 400;

            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(swfPath))) {
                SWF swf = new SWF(in, false);
                List<ScriptPack> packs = new ArrayList<>();
                for (ScriptPack pack : swf.getAS3Packs()) {
                    if (className.equals(pack.getPathScriptName())) {
                        packs.add(pack);
                    }
                }
                if (packs.size() != 1) {
                    throw new IllegalStateException("class packs=" + packs.size());
                }
                ABC abc = packs.get(0).abc;
                int classIndex = abc.findClassByName(className);
                int bodyIndex = -1;
                List<Trait> methodTraits = new ArrayList<>();
                methodTraits.addAll(abc.class_info.get(classIndex).static_traits.traits);
                methodTraits.addAll(abc.instance_info.get(classIndex).instance_traits.traits);
                for (Trait trait : methodTraits) {
                    if (!(trait instanceof TraitMethodGetterSetter m)) {
                        continue;
                    }
                    Multiname name = abc.constants.getMultiname(trait.name_index);
                    if (name.hasOwnName() && methodName.equals(abc.constants.getString(name.name_index))) {
                        bodyIndex = abc.findBodyIndex(m.method_info);
                    }
                }
                MethodBody body = abc.bodies.get(bodyIndex);
                AVM2Code code = body.getCode();
                AVM2ConstantPool cp = abc.constants;
                System.out.println("BODY_INDEX=" + bodyIndex);
                System.out.println("INSTRUCTION_COUNT=" + code.code.size());
                System.out.println("MAX_STACK=" + body.max_stack
                        + " INIT_SCOPE=" + body.init_scope_depth + " MAX_SCOPE=" + body.max_scope_depth);
                System.out.println("EXCEPTIONS=" + body.exceptions.length);
                boolean skipIgnored = args.length > 5 && "skipignored".equalsIgnoreCase(args[5]);
                boolean reachOnly = args.length > 5 && "reach".equalsIgnoreCase(args[5]);
                boolean[] reach = reachOnly ? reachable(code) : null;
                int end = Math.min(code.code.size(), from + count);
                for (int i = from; i < end; i++) {
                    AVM2Instruction ins = code.code.get(i);
                    if (skipIgnored && ins.isIgnored()) {
                        continue;
                    }
                    if (reachOnly && !reach[i]) {
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("%6d  addr=0x%04x len=%2d %s %-16s", i, ins.getAddress(),
                            ins.getBytesLength(), ins.isIgnored() ? "IGN" : "   ",
                            ins.definition.instructionName));
                    if (ins.isBranch() || ins.isJump()) {
                        long t = ins.getTargetAddress();
                        int tp = -1;
                        try {
                            tp = code.adr2pos(t);
                        } catch (Exception ignored) {
                            tp = -1;
                        }
                        sb.append(String.format(" tgt=0x%04x(#%d)", t, tp));
                    }
                    sb.append(" op=[");
                    int[] ops = ops(ins);
                    for (int k = 0; k < ops.length; k++) {
                        if (k > 0) {
                            sb.append(',');
                        }
                        sb.append(ops[k]);
                    }
                    sb.append(']');
                    String extra = describe(cp, ins);
                    if (!extra.isEmpty()) {
                        sb.append("  ").append(extra);
                    }
                    System.out.println(sb);
                }
            }
        } catch (Throwable error) {
            System.err.println("ERROR=" + error);
            System.exit(1);
        }
    }

    static int[] ops(AVM2Instruction ins) {
        return ins.operands == null ? new int[0] : ins.operands;
    }

    /** Marks instructions reachable from entry point 0, following fallthrough and branch targets. */
    static boolean[] reachable(AVM2Code code) {
        int n = code.code.size();
        boolean[] reach = new boolean[n];
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        if (n > 0) {
            work.add(0);
        }
        while (!work.isEmpty()) {
            int i = work.poll();
            if (i < 0 || i >= n || reach[i]) {
                continue;
            }
            reach[i] = true;
            AVM2Instruction ins = code.code.get(i);
            String name = ins.definition.instructionName;
            java.util.List<Integer> succ = new ArrayList<>();
            if ("lookupswitch".equals(name)) {
                succ.add(i + 1);
            } else if (ins.isExit()) {
                // no successors
            } else if ("jump".equals(name)) {
                succ.add(targetPos(code, ins));
            } else if (ins.isBranch() || ins.isJump()) {
                succ.add(targetPos(code, ins));
                succ.add(i + 1);
            } else {
                succ.add(i + 1);
            }
            for (int s : succ) {
                if (s >= 0) {
                    work.add(s);
                }
            }
        }
        return reach;
    }

    static int targetPos(AVM2Code code, AVM2Instruction ins) {
        try {
            return code.adr2pos(ins.getTargetAddress());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String describe(AVM2ConstantPool cp, AVM2Instruction ins) {
        String n = ins.definition.instructionName;
        try {
            switch (n) {
                case "pushstring":
                case "pushdouble":
                case "pushint":
                case "pushuint":
                case "pushfloat":
                case "pushfloat4":
                case "pushshort":
                case "pushbyte":
                    return "value=" + ins.definition.instructionName + ":" + rawValue(cp, ins);
                case "findpropstrict":
                case "findproperty":
                case "getproperty":
                case "setproperty":
                case "callpropvoid":
                case "callproperty":
                case "getlex":
                case "constructprop":
                case "getlocal":
                case "setlocal":
                case "coerce":
                case "call":
                case "newarray":
                case "newobject":
                    return "mn=" + multiname(cp, ins);
                default: {
                    String mn = ops(ins).length >= 1 ? multiname(cp, ins) : "";
                    return mn == null || mn.isEmpty() ? "" : "mn=" + mn;
                }
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    static Object rawValue(AVM2ConstantPool cp, AVM2Instruction ins) {
        String n = ins.definition.instructionName;
        int op = ops(ins).length > 0 ? ins.operands[0] : 0;
        switch (n) {
            case "pushstring":
                return cp.getString(op);
            case "pushdouble":
                return cp.getDouble(op);
            case "pushint":
                return cp.getInt(op);
            case "pushuint":
                return cp.getUInt(op);
            case "pushbyte":
            case "pushshort":
                return op;
            default:
                return op;
        }
    }

    static String multiname(AVM2ConstantPool cp, AVM2Instruction ins) {
        if (ops(ins).length == 0) {
            return "";
        }
        Multiname mn = cp.getMultiname(ins.operands[0]);
        if (mn == null) {
            return "<null>";
        }
        if (mn.hasOwnName() && mn.name_index >= 0 && mn.name_index < cp.getStringCount()) {
            return cp.getString(mn.name_index);
        }
        return mn.toString();
    }
}
