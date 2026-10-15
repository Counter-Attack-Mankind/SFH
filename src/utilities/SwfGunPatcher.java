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
import com.jpexs.decompiler.flash.tags.Tag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Minimal, fail-closed AVM2 patcher for Stats_Guns.Init/addGun registrations.
 *
 * This tool intentionally supports only the verified damage field. It uses the
 * FFDec Java model to locate a class and method, then identifies an addGun
 * registration by its first string argument (weapon id). It never edits an ABC
 * constant-pool entry and refuses zero or multiple semantic matches.
 */
public final class SwfGunPatcher {
    private static final String TARGET_FIELD = "damage";
    private static final int LOOK_BEHIND = 16;
    private static final int LOOK_AHEAD = 160;

    private record Options(
            String mode,
            Path input,
            Path output,
            String className,
            String methodName,
            String weaponId,
            String field,
            int expectedValue,
            int newValue) {
    }

    private record Match(
            ABC abc,
            MethodBody body,
            AVM2Code code,
            int bodyIndex,
            int weaponInstruction,
            int valueInstruction,
            long valueAddress,
            int value) {
    }

    public static void main(String[] args) {
        try {
            if (args.length >= 2 && "list-weapons".equalsIgnoreCase(args[0])) {
                listWeapons(Path.of(args[1]));
                return;
            }
            if (args.length >= 2 && "list-fields".equalsIgnoreCase(args[0])) {
                listFields();
                return;
            }
            if (args.length >= 3 && "patch-config".equalsIgnoreCase(args[0])) {
                patchConfig(Path.of(args[1]), Path.of(args[2]));
                return;
            }
            if (args.length >= 3 && "validate-config".equalsIgnoreCase(args[0])) {
                validateConfig(Path.of(args[1]), Path.of(args[2]));
                return;
            }
            if (args.length >= 3 && "verify-config".equalsIgnoreCase(args[0])) {
                verifyConfig(Path.of(args[1]), Path.of(args[2]),
                        args.length >= 4 ? Path.of(args[3]) : null);
                return;
            }
            Options options = parseArgs(args);
            Result result = run(options);
            System.out.println("STATUS=OK");
            System.out.println("MODE=" + options.mode());
            System.out.println("CLASS=" + options.className());
            System.out.println("METHOD=" + options.methodName());
            System.out.println("WEAPON=" + options.weaponId());
            System.out.println("FIELD=" + options.field());
            System.out.println("VALUE=" + result.value());
            System.out.println("ABC_METHOD_BODY_INDEX=" + result.bodyIndex());
            System.out.println("AVM2_ADDRESS=0x" + Long.toHexString(result.address()));
            System.out.println("INPUT_SHA256=" + sha256(options.input()));
            if (options.output() != null) {
                System.out.println("OUTPUT=" + options.output().toAbsolutePath().normalize());
                System.out.println("OUTPUT_SHA256=" + sha256(options.output()));
            }
        } catch (Throwable error) {
            System.err.println("STATUS=ERROR");
            System.err.println("ERROR=" + error.getMessage());
            System.exit(1);
        }
    }

    private record Result(int value, int bodyIndex, long address) {
    }

    private static Result run(Options options) throws Exception {
        if (!Files.isRegularFile(options.input())) {
            throw new IllegalArgumentException("Input SWF does not exist: " + options.input());
        }
        if (!TARGET_FIELD.equals(options.field())) {
            throw new IllegalArgumentException("Unsupported field: " + options.field());
        }
        if (options.expectedValue() < -128 || options.expectedValue() > 127
                || options.newValue() < -128 || options.newValue() > 127) {
            throw new IllegalArgumentException("The verified damage patch currently requires pushbyte values (-128..127)");
        }

        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(options.input().toFile()))) {
            SWF swf = new SWF(input, false);
            Match match = locate(swf, options);

            if (match.value() != options.expectedValue()) {
                throw new IllegalStateException(
                        "Expected original " + options.expectedValue() + " but found " + match.value());
            }

            if ("verify".equals(options.mode())) {
                return new Result(match.value(), match.bodyIndex(), match.valueAddress());
            }

            Path normalizedInput = options.input().toAbsolutePath().normalize();
            Path normalizedOutput = options.output().toAbsolutePath().normalize();
            if (normalizedInput.equals(normalizedOutput)) {
                throw new IllegalArgumentException("Output must not overwrite input");
            }

            AVM2Instruction instruction = match.code().code.get(match.valueInstruction());
            instruction.setOperand(0, options.newValue(), match.code(), match.body());
            match.body().setModified();
            ((Tag) match.abc().parentTag).setModified(true);

            Path parent = normalizedOutput.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = normalizedOutput.resolveSibling(normalizedOutput.getFileName() + ".partial");
            Files.deleteIfExists(temporary);
            try {
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary.toFile()))) {
                    swf.saveTo(output);
                }
                verifyWritten(temporary, options, options.newValue());
                Files.move(temporary, normalizedOutput,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable error) {
                Files.deleteIfExists(temporary);
                throw error;
            }
            return new Result(options.newValue(), match.bodyIndex(), match.valueAddress());
        }
    }

    private static void verifyWritten(Path output, Options original, int expected) throws Exception {
        Options verify = new Options("verify", output, null, original.className(), original.methodName(),
                original.weaponId(), original.field(), expected, expected);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(output.toFile()))) {
            SWF reread = new SWF(input, false);
            Match match = locate(reread, verify);
            if (match.value() != expected) {
                throw new IllegalStateException("Post-write verification failed: found " + match.value());
            }
        }
    }

    private static Match locate(SWF swf, Options options) throws Exception {
        List<ScriptPack> packs = new ArrayList<>();
        for (ScriptPack pack : swf.getAS3Packs()) {
            if (options.className().equals(pack.getPathScriptName())) {
                packs.add(pack);
            }
        }
        if (packs.size() != 1) {
            throw new IllegalStateException("Expected one class " + options.className() + ", found " + packs.size());
        }

        ABC abc = packs.get(0).abc;
        int classIndex = abc.findClassByName(options.className());
        if (classIndex < 0) {
            throw new IllegalStateException("Class definition not found: " + options.className());
        }
        int bodyIndex = -1;
        int namedStaticMethods = 0;
        for (Trait trait : abc.class_info.get(classIndex).static_traits.traits) {
            if (!(trait instanceof TraitMethodGetterSetter methodTrait)) {
                continue;
            }
            Multiname name = abc.constants.getMultiname(trait.name_index);
            if (name.hasOwnName()
                    && options.methodName().equals(abc.constants.getString(name.name_index))) {
                namedStaticMethods++;
                bodyIndex = abc.findBodyIndex(methodTrait.method_info);
            }
        }
        if (namedStaticMethods != 1) {
            throw new IllegalStateException("Expected one static method " + options.methodName()
                    + ", found " + namedStaticMethods);
        }
        if (bodyIndex < 0 || bodyIndex >= abc.bodies.size()) {
            throw new IllegalStateException("Method body not found: " + options.className() + "." + options.methodName());
        }
        MethodBody body = abc.bodies.get(bodyIndex);
        AVM2Code code = body.getCode();
        AVM2ConstantPool constants = abc.constants;
        List<Match> matches = new ArrayList<>();

        for (int i = 0; i < code.code.size(); i++) {
            AVM2Instruction instruction = code.code.get(i);
            if (!"pushstring".equals(instruction.definition.instructionName)
                    || instruction.operands.length != 1
                    || !options.weaponId().equals(constants.getString(instruction.operands[0]))) {
                continue;
            }
            if (!isRegistrationId(code, constants, i)) {
                continue;
            }
            int damageInstruction = findDamageInstruction(code, constants, i);
            if (damageInstruction >= 0) {
                AVM2Instruction damage = code.code.get(damageInstruction);
                matches.add(new Match(abc, body, code, bodyIndex, i, damageInstruction,
                        damage.getAddress(), signedByte(damage.operands[0])));
            }
        }

        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one semantic addGun match for weapon "
                    + options.weaponId() + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static boolean isRegistrationId(AVM2Code code, AVM2ConstantPool constants, int weaponIndex) {
        int numericPushes = 0;
        for (int i = weaponIndex - 1; i >= 0 && i >= weaponIndex - LOOK_BEHIND; i--) {
            AVM2Instruction instruction = code.code.get(i);
            String name = instruction.definition.instructionName;
            if ("pushbyte".equals(name) || "pushshort".equals(name) || "pushint".equals(name)
                    || "pushuint".equals(name) || "pushdouble".equals(name)) {
                numericPushes++;
            }
            if ("findpropstrict".equals(name)) {
                return numericPushes == 1 && "addGun".equals(multinameName(constants, instruction));
            }
        }
        return false;
    }

    private static int findDamageInstruction(AVM2Code code, AVM2ConstantPool constants, int weaponIndex) {
        List<String> strings = new ArrayList<>();
        List<Integer> numerics = new ArrayList<>();
        for (int i = weaponIndex + 1; i < code.code.size() && i <= weaponIndex + LOOK_AHEAD; i++) {
            AVM2Instruction instruction = code.code.get(i);
            String name = instruction.definition.instructionName;
            if ("pushstring".equals(name)) {
                strings.add(constants.getString(instruction.operands[0]));
            } else if ("pushbyte".equals(name)) {
                numerics.add(i);
            } else if ("callpropvoid".equals(name)) {
                if ("addGun".equals(multinameName(constants, instruction))) {
                    break;
                }
            }

            // addGun parameters after weapon id: sprite, display name, type name,
            // cost, level requirement, damage. USP is known to use empty sprite
            // and display-name strings. Requiring this exact prefix prevents the
            // later USP2 registration from being mistaken for USP.
            if (strings.size() >= 3 && numerics.size() >= 3) {
                if ("".equals(strings.get(0)) && "".equals(strings.get(1))) {
                    return numerics.get(2);
                }
                return -1;
            }
        }
        return -1;
    }

    private static String multinameName(AVM2ConstantPool constants, AVM2Instruction instruction) {
        if (instruction.operands.length == 0) {
            return "";
        }
        Multiname multiname = constants.getMultiname(instruction.operands[0]);
        return multiname != null && multiname.hasOwnName()
                ? constants.getString(multiname.name_index)
                : "";
    }

    private static int signedByte(int value) {
        return (byte) (value & 0xff);
    }

    private static Options parseArgs(String[] args) {
        if (args.length != 7 && args.length != 9) {
            throw new IllegalArgumentException(
                    "Usage: patch <input> <output> <class> <method> <weapon> <field> <expected> <new> "
                            + "or verify <input> <class> <method> <weapon> <field> <expected>");
        }
        String mode = args[0].toLowerCase();
        if ("patch".equals(mode) && args.length == 9) {
            return new Options(mode, Path.of(args[1]), Path.of(args[2]), args[3], args[4], args[5], args[6],
                    Integer.parseInt(args[7]), Integer.parseInt(args[8]));
        }
        if ("verify".equals(mode) && args.length == 7) {
            return new Options(mode, Path.of(args[1]), null, args[2], args[3], args[4], args[5],
                    Integer.parseInt(args[6]), Integer.parseInt(args[6]));
        }
        throw new IllegalArgumentException("Invalid mode or argument count");
    }

    /** Batch build from a weapon pack configuration file. */
    private static void patchConfig(Path configFile, Path projectRoot) throws Exception {
        WeaponPackPatcher.Outcome outcome = WeaponPackPatcher.build(configFile, projectRoot);
        System.out.println("STATUS=OK");
        System.out.println("MODE=patch-config");
        System.out.println("CONFIG=" + configFile.toAbsolutePath().normalize());
        System.out.println("PROJECT_ROOT=" + projectRoot.toAbsolutePath().normalize());
        System.out.println("INPUT=" + outcome.input);
        System.out.println("INPUT_SHA256=" + outcome.inputSha256);
        System.out.println("OUTPUT=" + outcome.output);
        System.out.println("OUTPUT_SHA256=" + outcome.outputSha256);
        System.out.println("REPORT=" + outcome.report);
        System.out.println("WEAPONS_RESOLVED=" + outcome.weaponsResolved);
        System.out.println("ARGUMENTS_COMPARED=" + outcome.argumentsCompared);
        System.out.println("UNEXPECTED_DIFFERENCES=0");
        System.out.println("CHANGES=" + outcome.lines.size());
        for (String line : outcome.lines) {
            System.out.println("  " + line);
        }
        System.out.println("RUNTIME_STATUS=manual_test_required");
    }

    /** Read-only dry run of a weapon pack configuration; used by the Validate Config task. */
    private static void validateConfig(Path configFile, Path projectRoot) throws Exception {
        int changes = WeaponPackPatcher.validate(configFile, projectRoot);
        System.out.println("STATUS=OK");
        System.out.println("MODE=validate-config");
        System.out.println("CONFIG=" + configFile.toAbsolutePath().normalize());
        System.out.println("CHANGES=" + changes);
    }

    /** Verifies a previously built weapon pack SWF; used by the Verify MOD task. */    private static void verifyConfig(Path configFile, Path projectRoot, Path override) throws Exception {
        System.out.println("STATUS=OK");
        System.out.println("MODE=verify-config");
        System.out.println("CONFIG=" + configFile.toAbsolutePath().normalize());
        WeaponPackPatcher.verify(configFile, projectRoot, override);
    }

    /** Documents which attributes can be patched and which are refused, with the reason. */
    private static void listFields() {
        System.out.println("SUPPORTED_FIELDS");
        for (WeaponField field : WeaponField.all()) {
            System.out.println("  " + pad(field.name, 12) + " addGun param" + pad(Integer.toString(field.param), 4)
                    + pad(field.type == WeaponField.Type.INTEGER ? "integer" : "number", 10)
                    + "range " + pad(field.describeRange(), 22) + field.assignment);
        }
        System.out.println("REFUSED_FIELDS");
        for (java.util.Map.Entry<String, String> entry : WeaponField.refusedFields().entrySet()) {
            System.out.println("  " + pad(entry.getKey(), 12) + entry.getValue());
        }
    }

    private static String pad(String text, int width) {
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /** Read-only dump of every resolved addGun registration; used by the List Weapons task. */
    private static void listWeapons(Path input) throws Exception {        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input SWF does not exist: " + input);
        }
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(input.toFile()))) {
            SWF swf = new SWF(stream, false);
            WeaponRegistry.Resolution resolution = WeaponRegistry.resolve(swf);
            String selfTest = WeaponRegistry.selfTest(resolution);
            System.out.println("ADDGUN_CALLS=" + resolution.all.size());
            System.out.println("RESOLVED_WEAPONS=" + resolution.byId.size());
            System.out.println("ABC_METHOD_BODY_INDEX=" + resolution.bodyIndex);
            System.out.println("RESOLVER_SELF_TEST=" + (selfTest == null ? "OK" : selfTest));
            for (WeaponRegistry.Registration reg : resolution.all) {
                if (!reg.resolved()) {
                    System.out.println("UNRESOLVED\t0x" + Long.toHexString(reg.callAddress) + "\t" + reg.problem);
                    continue;
                }
                WeaponRegistry.Slot[] args = reg.args;
                StringBuilder shape = new StringBuilder();
                for (int i = 0; i < WeaponRegistry.ARG_COUNT; i++) {
                    if (i > 0) {
                        shape.append(',');
                    }
                    shape.append(i + 1).append('=').append(args[i].display());
                }
                System.out.println("WEAPON\t" + reg.weaponId() + "\t" + reg.callIndex
                        + "\t0x" + Long.toHexString(reg.callAddress) + "\t" + shape);
            }
            for (String problem : resolution.problems) {
                System.out.println("PROBLEM=" + problem);
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }
}
