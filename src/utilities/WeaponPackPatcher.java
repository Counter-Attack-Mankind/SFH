import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.types.MethodBody;
import com.jpexs.decompiler.flash.tags.Tag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.HexFormat;

/**
 * Applies a {@link WeaponPackConfig} to the original SWF with all-or-nothing semantics.
 *
 * <p>The build runs as a transaction: every target is resolved and every original value confirmed
 * before a single instruction is touched; all edits are then written to a temporary artifact which
 * is re-parsed and compared field by field against the unmodified baseline; only a fully verified
 * artifact is promoted to the configured output path. Any failure deletes the temporary artifact,
 * the official output and any stale success report.</p>
 */
public final class WeaponPackPatcher {

    /** The detailed result of a successful build, also used to render the build report. */
    public static final class Outcome {
        public final Path input;
        public final Path output;
        public final Path report;
        public final String inputSha256;
        public final String outputSha256;
        public final int weaponsResolved;
        public final int argumentsCompared;
        public final List<String> lines = new ArrayList<>();

        Outcome(Path input, Path output, Path report, String inputSha256, String outputSha256,
                int weaponsResolved, int argumentsCompared) {
            this.input = input;
            this.output = output;
            this.report = report;
            this.inputSha256 = inputSha256;
            this.outputSha256 = outputSha256;
            this.weaponsResolved = weaponsResolved;
            this.argumentsCompared = argumentsCompared;
        }
    }

    private static final class Edit {
        final int position;
        final WeaponRegistry.Registration registration;
        final WeaponPackConfig.Change change;
        final Avm2ConstantEditor.Encoded encoded;
        final String before;

        Edit(int position, WeaponRegistry.Registration registration, WeaponPackConfig.Change change,
             Avm2ConstantEditor.Encoded encoded, String before) {
            this.position = position;
            this.registration = registration;
            this.change = change;
            this.encoded = encoded;
            this.before = before;
        }

        String key() {
            return registration.weaponId() + "#" + change.field.param;
        }
    }

    private WeaponPackPatcher() {
    }

    // ------------------------------------------------------------------ build

    public static Outcome build(Path configFile, Path projectRoot) throws Exception {
        WeaponPackConfig config = WeaponPackConfig.load(configFile);
        Path input = config.input(projectRoot);
        Path output = config.output(projectRoot);
        Path report = output.resolveSibling(output.getFileName() + ".build.json");

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input SWF does not exist: " + input);
        }
        if (input.equals(output)) {
            throw new IllegalArgumentException("The configured output must not be the original SWF: " + output);
        }
        Path buildRoot = projectRoot.resolve("builds").toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        if (!normalizedOutput.startsWith(buildRoot)) {
            throw new IllegalArgumentException("The configured output must stay inside " + buildRoot
                    + " so the original game files are never overwritten: " + normalizedOutput);
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = output.resolveSibling(output.getFileName() + ".partial");
        Files.deleteIfExists(temporary);

        try {
            SWF swf = readSwf(input);
            WeaponRegistry.Resolution baseline = WeaponRegistry.resolve(swf);
            requireResolvable(baseline);
            requireGroundTruth(baseline);

            Map<Integer, List<String>> consumers = argumentConsumers(baseline);
            Map<String, String> before = signature(baseline);

            List<Edit> edits = new ArrayList<>();
            for (WeaponPackConfig.Change change : config.changes) {
                edits.add(plan(baseline, consumers, change));
            }

            // Group by instruction position so a value shared through a duplicated stack slot is
            // rewritten once, and only when every consumer of that instruction is being changed to
            // the same value.
            Map<Integer, List<Edit>> groups = new LinkedHashMap<>();
            for (Edit edit : edits) {
                groups.computeIfAbsent(edit.position, key -> new ArrayList<>()).add(edit);
            }
            for (Map.Entry<Integer, List<Edit>> group : groups.entrySet()) {
                List<Edit> sharing = group.getValue();
                Set<String> targeted = new HashSet<>();
                double value = sharing.get(0).change.value;
                for (Edit edit : sharing) {
                    if (Double.compare(edit.change.value, value) != 0) {
                        throw new IllegalStateException("Conflicting modifications of the same instruction: "
                                + describe(sharing));
                    }
                    targeted.add(edit.key());
                }
                List<String> all = consumers.getOrDefault(group.getKey(), List.of());
                for (String consumer : all) {
                    if (!targeted.contains(consumer)) {
                        throw new IllegalStateException("Modification of " + describe(sharing)
                                + " would also change " + consumer
                                + " because both read the same instruction");
                    }
                }
            }

            List<Integer> positions = new ArrayList<>(groups.keySet());
            positions.sort(Comparator.reverseOrder());
            AVM2Code code = baseline.code;
            int codeSize = code.code.size();
            for (Integer position : positions) {
                code.replaceInstruction(position, groups.get(position).get(0).encoded.instruction, baseline.body);
            }
            if (code.code.size() != codeSize) {
                throw new IllegalStateException("Instruction rewrite changed the instruction count from "
                        + codeSize + " to " + code.code.size() + "; refusing to continue");
            }
            baseline.body.setModified();
            ((Tag) baseline.abc.parentTag).setModified(true);

            writeSwf(swf, temporary);

            WeaponRegistry.Resolution after = WeaponRegistry.resolve(readSwf(temporary));
            requireResolvable(after);
            Map<String, String> now = signature(after);

            Set<String> expectedKeys = new HashSet<>();
            for (Edit edit : edits) {
                expectedKeys.add(edit.key());
            }
            Set<String> changedKeys = new TreeSet<>();
            if (!before.keySet().equals(now.keySet())) {
                throw new IllegalStateException("Post-write verification failed: the set of resolved weapons changed");
            }
            for (String key : before.keySet()) {
                if (!before.get(key).equals(now.get(key))) {
                    changedKeys.add(key);
                }
            }
            if (!changedKeys.equals(expectedKeys)) {
                Set<String> unexpected = new TreeSet<>(changedKeys);
                unexpected.removeAll(expectedKeys);
                Set<String> missing = new TreeSet<>(expectedKeys);
                missing.removeAll(changedKeys);
                throw new IllegalStateException("Post-write verification failed: unexpected changed arguments "
                        + unexpected + ", unchanged targets " + missing);
            }

            Outcome outcome = new Outcome(input, output, report, sha256(input), null,
                    after.byId.size(), before.size());
            for (Edit edit : edits) {
                String key = edit.key();
                double actual = numeric(after, key);
                if (Double.compare(actual, edit.change.value) != 0) {
                    throw new IllegalStateException("Post-write verification failed for " + edit.change.weaponId
                            + " " + edit.change.field.name + ": expected " + edit.change.value
                            + " but the rewritten SWF registers " + actual);
                }
                outcome.lines.add(edit.change.weaponId + "." + edit.change.field.name
                        + " (addGun param" + edit.change.field.param + "): "
                        + Avm2ConstantEditor.format(edit.change.expected) + " -> "
                        + Avm2ConstantEditor.format(edit.change.value) + " via " + edit.encoded.describe());
            }

            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            String outputSha = sha256(output);
            Outcome finished = new Outcome(input, output, report, outcome.inputSha256, outputSha,
                    outcome.weaponsResolved, outcome.argumentsCompared);
            finished.lines.addAll(outcome.lines);
            Files.writeString(report, renderReport(config, finished), StandardCharsets.UTF_8);
            return finished;
        } catch (Throwable error) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(output);
            Files.deleteIfExists(report);
            throw error;
        }
    }

    /** Cheap structural sanity check; valid on both the original and a rewritten SWF. */
    private static void requireResolvable(WeaponRegistry.Resolution resolution) {
        if (resolution.byId.isEmpty()) {
            throw new IllegalStateException("No addGun registrations could be resolved in "
                    + WeaponRegistry.CLASS_PATH + "." + WeaponRegistry.METHOD_NAME);
        }
    }

    /**
     * The resolver is calibrated against hand-read ground truth from the decompiled source. That
     * ground truth only describes the ORIGINAL SWF, so this check must never run on a patched file.
     */
    private static void requireGroundTruth(WeaponRegistry.Resolution resolution) {
        String selfTest = WeaponRegistry.selfTest(resolution);
        if (selfTest != null) {
            throw new IllegalStateException("Resolver self-test failed against the known ground truth: " + selfTest);
        }
    }

    private static Edit plan(WeaponRegistry.Resolution baseline, Map<Integer, List<String>> consumers,
                             WeaponPackConfig.Change change) {
        WeaponRegistry.Registration registration = baseline.byId.get(change.weaponId);
        if (registration == null) {
            throw new IllegalArgumentException("Weapon '" + change.weaponId + "' is not registered in "
                    + WeaponRegistry.CLASS_PATH + "." + WeaponRegistry.METHOD_NAME
                    + suggest(baseline, change.weaponId));
        }
        WeaponRegistry.Slot slot = registration.arg(change.field.param);
        if (slot == null || !slot.constant) {
            throw new IllegalStateException("Weapon '" + change.weaponId + "' parameter "
                    + change.field.param + " (" + change.field.name + ") is not a compile-time constant");
        }
        double actual = slot.numeric();
        if (Double.isNaN(actual)) {
            throw new IllegalStateException("Weapon '" + change.weaponId + "' parameter "
                    + change.field.param + " (" + change.field.name + ") is not numeric");
        }
        if (Double.compare(actual, change.expected) != 0) {
            throw new IllegalStateException("Weapon '" + change.weaponId + "' field '" + change.field.name
                    + "': configuration expects the original value " + Avm2ConstantEditor.format(change.expected)
                    + " but the SWF registers " + Avm2ConstantEditor.format(actual)
                    + " at addGun parameter " + change.field.param);
        }
        int position = slot.producer;
        if (position < 0 || position >= baseline.code.code.size()) {
            throw new IllegalStateException("Weapon '" + change.weaponId + "' field '" + change.field.name
                    + "': the producing instruction could not be located");
        }
        AVM2Instruction instruction = baseline.code.code.get(position);
        if (!Avm2ConstantEditor.isNumericPush(instruction)) {
            throw new IllegalStateException("Weapon '" + change.weaponId + "' field '" + change.field.name
                    + "': the argument is produced by '" + instruction.definition.instructionName
                    + "', which is not a plain numeric constant and cannot be rewritten safely");
        }
        Avm2ConstantEditor.Encoded encoded = Avm2ConstantEditor.encode(baseline.abc, instruction, change.value);
        return new Edit(position, registration, change, encoded, instruction.definition.instructionName);
    }

    private static String suggest(WeaponRegistry.Resolution baseline, String wanted) {
        List<String> close = new ArrayList<>();
        for (String id : baseline.byId.keySet()) {
            if (id.equalsIgnoreCase(wanted) || id.contains(wanted) || wanted.contains(id)) {
                close.add(id);
            }
        }
        return close.isEmpty() ? "" : "; did you mean " + String.join(", ", close) + "?";
    }

    /** Maps every instruction index to the addGun arguments it produces across all registrations. */
    private static Map<Integer, List<String>> argumentConsumers(WeaponRegistry.Resolution resolution) {
        Map<Integer, List<String>> consumers = new HashMap<>();
        for (WeaponRegistry.Registration registration : resolution.byId.values()) {
            String id = registration.weaponId();
            for (int param = 1; param <= WeaponRegistry.ARG_COUNT; param++) {
                WeaponRegistry.Slot slot = registration.arg(param);
                if (slot != null && slot.producer >= 0) {
                    consumers.computeIfAbsent(slot.producer, key -> new ArrayList<>()).add(id + "#" + param);
                }
            }
        }
        return consumers;
    }

    /** Renders one entry per weapon parameter; used to diff the patched SWF against the baseline. */
    private static Map<String, String> signature(WeaponRegistry.Resolution resolution) {
        Map<String, String> values = new LinkedHashMap<>();
        for (WeaponRegistry.Registration registration : resolution.byId.values()) {
            String id = registration.weaponId();
            for (int param = 1; param <= WeaponRegistry.ARG_COUNT; param++) {
                WeaponRegistry.Slot slot = registration.arg(param);
                values.put(id + "#" + param, slot == null ? "<null>" : slot.display());
            }
        }
        return values;
    }

    private static double numeric(WeaponRegistry.Resolution resolution, String key) {
        int hash = key.lastIndexOf('#');
        String id = key.substring(0, hash);
        int param = Integer.parseInt(key.substring(hash + 1));
        WeaponRegistry.Registration registration = resolution.byId.get(id);
        if (registration == null) {
            throw new IllegalStateException("weapon '" + id + "' disappeared after the rewrite");
        }
        return registration.arg(param).numeric();
    }

    private static String describe(List<Edit> edits) {
        List<String> parts = new ArrayList<>();
        for (Edit edit : edits) {
            parts.add(edit.change.weaponId + "." + edit.change.field.name);
        }
        return String.join(" + ", parts);
    }

    // ------------------------------------------------------------------ validate

    /**
     * Read-only dry run: every check the build performs except the rewrite itself. Resolves the
     * original SWF, confirms each target weapon/field is modifiable, confirms every declared
     * original value, and reports the encoding each change would use. Nothing is written.
     */
    public static int validate(Path configFile, Path projectRoot) throws Exception {
        WeaponPackConfig config = WeaponPackConfig.load(configFile);
        Path input = config.input(projectRoot);
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input SWF does not exist: " + input);
        }
        System.out.println("VALIDATE INPUT=" + input);
        SWF swf = readSwf(input);
        WeaponRegistry.Resolution baseline = WeaponRegistry.resolve(swf);
        requireResolvable(baseline);
        requireGroundTruth(baseline);
        System.out.println("VALIDATE ADDGUN_CALLS=" + baseline.all.size());
        System.out.println("VALIDATE RESOLVED_WEAPONS=" + baseline.byId.size());
        System.out.println("VALIDATE ABC_METHOD_BODY_INDEX=" + baseline.bodyIndex);

        Map<Integer, List<String>> consumers = argumentConsumers(baseline);
        List<Edit> edits = new ArrayList<>();
        for (WeaponPackConfig.Change change : config.changes) {
            Edit edit = plan(baseline, consumers, change);
            edits.add(edit);
            System.out.println("VALIDATE OK " + change.weaponId + "." + change.field.name
                    + " (addGun param" + change.field.param + "): "
                    + Avm2ConstantEditor.format(change.expected) + " -> "
                    + Avm2ConstantEditor.format(change.value) + " via " + edit.encoded.describe());
        }

        // Conflicting writes: two changes that resolve to the same instruction must agree, and that
        // instruction must not also feed an argument nobody asked to change.
        Map<Integer, List<Edit>> groups = new LinkedHashMap<>();
        for (Edit edit : edits) {
            groups.computeIfAbsent(edit.position, key -> new ArrayList<>()).add(edit);
        }
        for (Map.Entry<Integer, List<Edit>> group : groups.entrySet()) {
            List<Edit> sharing = group.getValue();
            Set<String> targeted = new HashSet<>();
            double value = sharing.get(0).change.value;
            for (Edit edit : sharing) {
                if (Double.compare(edit.change.value, value) != 0) {
                    throw new IllegalStateException("Conflicting modifications of the same instruction: "
                            + describe(sharing));
                }
                targeted.add(edit.key());
            }
            for (String consumer : consumers.getOrDefault(group.getKey(), List.of())) {
                if (!targeted.contains(consumer)) {
                    throw new IllegalStateException("Modification of " + describe(sharing)
                            + " would also change " + consumer
                            + " because both read the same instruction");
                }
            }
        }
        System.out.println("VALIDATE CHANGES=" + edits.size());
        System.out.println("VALIDATE SHARED_LOCATIONS=" + (edits.size() - groups.size()));
        System.out.println("VALIDATE STATUS=OK");
        return edits.size();
    }

    // ------------------------------------------------------------------ verify

    public static void verify(Path configFile, Path projectRoot, Path override) throws Exception {
        WeaponPackConfig config = WeaponPackConfig.load(configFile);
        Path target = override != null ? override : config.output(projectRoot);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("SWF to verify does not exist: " + target);
        }
        SWF swf = readSwf(target);
        WeaponRegistry.Resolution resolution = WeaponRegistry.resolve(swf);
        requireResolvable(resolution);

        Set<String> expectedKeys = new LinkedHashSet<>();
        for (WeaponPackConfig.Change change : config.changes) {
            WeaponRegistry.Registration registration = resolution.byId.get(change.weaponId);
            if (registration == null) {
                throw new IllegalStateException("Weapon '" + change.weaponId + "' is missing from " + target);
            }
            WeaponRegistry.Slot slot = registration.arg(change.field.param);
            if (slot == null || !slot.constant) {
                throw new IllegalStateException("Weapon '" + change.weaponId + "' field '" + change.field.name
                        + "' is not a compile-time constant in " + target);
            }
            double actual = slot.numeric();
            if (Double.compare(actual, change.value) != 0) {
                throw new IllegalStateException("Weapon '" + change.weaponId + "' field '" + change.field.name
                        + "' is " + Avm2ConstantEditor.format(actual) + " but the configuration requires "
                        + Avm2ConstantEditor.format(change.value));
            }
            AVM2Instruction instruction = resolution.code.code.get(slot.producer);
            System.out.println("VERIFIED " + change.weaponId + "." + change.field.name
                    + " = " + Avm2ConstantEditor.format(actual)
                    + " (addGun param" + change.field.param + ", " + instruction.definition.instructionName + ")");
            expectedKeys.add(change.weaponId + "#" + change.field.param);
        }
        System.out.println("WEAPONS_RESOLVED=" + resolution.byId.size());
        System.out.println("CHANGES_VERIFIED=" + config.changes.size());

        // Isolation check: re-resolve the untouched original and prove that the ONLY registrations
        // that differ from the built SWF are the configured ones. This is what catches a rewrite
        // that accidentally corrupted a shared constant or another weapon's argument.
        Path original = config.input(projectRoot);
        if (Files.isRegularFile(original) && !original.equals(target)) {
            WeaponRegistry.Resolution pristine = WeaponRegistry.resolve(readSwf(original));
            requireResolvable(pristine);
            Map<String, String> before = signature(pristine);
            Map<String, String> now = signature(resolution);
            if (!before.keySet().equals(now.keySet())) {
                throw new IllegalStateException("The built SWF registers a different set of weapon arguments "
                        + "than the original SWF");
            }
            Set<String> changed = new TreeSet<>();
            for (String key : before.keySet()) {
                if (!before.get(key).equals(now.get(key))) {
                    changed.add(key);
                }
            }
            if (!changed.equals(expectedKeys)) {
                Set<String> unexpected = new TreeSet<>(changed);
                unexpected.removeAll(expectedKeys);
                Set<String> missing = new TreeSet<>(expectedKeys);
                missing.removeAll(changed);
                throw new IllegalStateException("Isolation check failed: unexpected changed arguments "
                        + unexpected + ", unchanged targets " + missing);
            }
            for (WeaponPackConfig.Change change : config.changes) {
                double was = numeric(pristine, change.weaponId + "#" + change.field.param);
                if (Double.compare(was, change.expected) != 0) {
                    throw new IllegalStateException("Original SWF value for " + change.weaponId + "."
                            + change.field.name + " is " + Avm2ConstantEditor.format(was)
                            + " but the configuration declares expected "
                            + Avm2ConstantEditor.format(change.expected));
                }
            }
            System.out.println("ISOLATION_CHECK=OK");
            System.out.println("CHANGED_ARGUMENTS=" + changed.size());
            System.out.println("ORIGINAL=" + original);
            String originalSha = sha256(original);
            System.out.println("ORIGINAL_SHA256=" + originalSha);
            // The build report records the SHA-256 of the input as it was when the MOD was built.
            // A match proves the original game file has not been touched since.
            Path report = Path.of(target.toString() + ".build.json");
            if (Files.isRegularFile(report)) {
                String recorded = recordedInputSha(report);
                if (recorded == null) {
                    System.out.println("ORIGINAL_UNMODIFIED=unknown (no input_sha256 in the build report)");
                } else if (recorded.equalsIgnoreCase(originalSha)) {
                    System.out.println("ORIGINAL_UNMODIFIED=OK (matches input_sha256 recorded in the build report)");
                } else {
                    throw new IllegalStateException("The original SWF has changed since the build: recorded "
                            + recorded + " but it is now " + originalSha);
                }
            } else {
                System.out.println("ORIGINAL_UNMODIFIED=unknown (no build report next to the built SWF)");
            }
        } else {
            System.out.println("ISOLATION_CHECK=SKIPPED (original input not available next to this build)");
        }
        System.out.println("TARGET=" + target);
        System.out.println("TARGET_SHA256=" + sha256(target));
    }

    private static String recordedInputSha(Path report) throws IOException {
        String text = Files.readString(report, StandardCharsets.UTF_8);
        com.eclipsesource.json.JsonValue root = com.eclipsesource.json.Json.parse(text);
        if (!root.isObject()) {
            return null;
        }
        com.eclipsesource.json.JsonValue value = root.asObject().get("input_sha256");
        return value != null && value.isString() ? value.asString() : null;
    }

    // ------------------------------------------------------------------ report

    private static String renderReport(WeaponPackConfig config, Outcome outcome) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": \"static_verified\",\n");
        json.append("  \"runtime_status\": \"manual_test_required\",\n");
        json.append("  \"mod_id\": ").append(quote(config.modId)).append(",\n");
        json.append("  \"description\": ").append(quote(config.description)).append(",\n");
        json.append("  \"config\": ").append(quote(config.configFile.toAbsolutePath().normalize().toString())).append(",\n");
        json.append("  \"input\": ").append(quote(outcome.input.toString())).append(",\n");
        json.append("  \"input_sha256\": ").append(quote(outcome.inputSha256)).append(",\n");
        json.append("  \"output\": ").append(quote(outcome.output.toString())).append(",\n");
        json.append("  \"output_sha256\": ").append(quote(outcome.outputSha256)).append(",\n");
        json.append("  \"changes\": [\n");
        for (int i = 0; i < outcome.lines.size(); i++) {
            json.append("    ").append(quote(outcome.lines.get(i)));
            json.append(i + 1 < outcome.lines.size() ? ",\n" : "\n");
        }
        json.append("  ],\n");
        json.append("  \"verified\": {\n");
        json.append("    \"weapons_resolved\": ").append(outcome.weaponsResolved).append(",\n");
        json.append("    \"arguments_compared\": ").append(outcome.argumentsCompared).append(",\n");
        json.append("    \"unexpected_differences\": 0,\n");
        json.append("    \"original_sha256_unchanged\": ")
                .append(outcome.inputSha256.equals(sha256Quiet(outcome.input)) ? "true" : "false").append("\n");
        json.append("  },\n");
        json.append("  \"patch_technology\": \"FFDec Java API; semantic AVM2 argument resolution through an "
                + "abstract stack interpretation; AVM2 instruction rewrite; no AS3 recompilation\",\n");
        json.append("  \"note\": \"Static verification only. It proves the SWF re-parses, that every target "
                + "argument holds the configured value and that no other registered argument changed. "
                + "It does not prove in-game behaviour; a manual game run is still required.\"\n");
        json.append("}\n");
        return json.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    // ------------------------------------------------------------------ io

    static SWF readSwf(Path path) throws IOException, InterruptedException {
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            return new SWF(stream, false);
        }
    }

    static void writeSwf(SWF swf, Path path) throws IOException, InterruptedException {
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(path.toFile()))) {
            swf.saveTo(stream);
        }
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static String sha256Quiet(Path path) {
        try {
            return sha256(path);
        } catch (Exception error) {
            return "";
        }
    }
}
