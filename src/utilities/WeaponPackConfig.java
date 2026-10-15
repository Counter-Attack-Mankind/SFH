import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validated, side-effect free model of a weapon pack configuration file.
 *
 * <p>{@link #load} performs every check that can be made without touching the SWF: member
 * duplication, required keys, unknown and refused field names, declared value ranges, duplicate
 * weapon entries, duplicate weapon/field pairs and contradictory expectations for one field. Only
 * after this stage succeeds does the caller open the original SWF.</p>
 */
public final class WeaponPackConfig {

    /** One requested attribute change. */
    public static final class Change {
        public final String weaponId;
        public final WeaponField field;
        public final double expected;
        public final double value;

        Change(String weaponId, WeaponField field, double expected, double value) {
            this.weaponId = weaponId;
            this.field = field;
            this.expected = expected;
            this.value = value;
        }

        public String key() {
            return weaponId + "/" + field.name;
        }
    }

    public final Path configFile;
    public final String modId;
    public final String description;
    public final String inputText;
    public final String outputText;
    public final List<Change> changes;

    private WeaponPackConfig(Path configFile, String modId, String description, String inputText,
                             String outputText, List<Change> changes) {
        this.configFile = configFile;
        this.modId = modId;
        this.description = description;
        this.inputText = inputText;
        this.outputText = outputText;
        this.changes = changes;
    }

    public Path resolve(Path projectRoot, String relative) {
        Path path = Path.of(relative);
        return path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
    }

    public Path input(Path projectRoot) {
        return resolve(projectRoot, inputText);
    }

    public Path output(Path projectRoot) {
        return resolve(projectRoot, outputText);
    }

    /**
     * Reads a configuration file as UTF-8 and drops a leading byte-order mark. Windows PowerShell's
     * {@code Set-Content -Encoding UTF8} and Notepad both write a BOM, which is not legal JSON and
     * would otherwise make the parser report "Expected value at 1:1".
     */
    private static String readText(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    /** True when this file uses the batch weapon-pack schema rather than the v0.1 single patch schema. */
    public static boolean isWeaponPack(Path file) throws IOException {
        String text = readText(file);
        JsonValue root = Json.parse(text);
        return root.isObject() && root.asObject().get("weapons") != null;
    }

    public static WeaponPackConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Configuration file does not exist: " + file);
        }
        String text = readText(file);

        List<String> duplicates = JsonLint.findDuplicateMembers(text);
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Configuration declares the same member more than once: "
                    + String.join(", ", duplicates));
        }

        JsonValue rootValue = Json.parse(text);
        if (!rootValue.isObject()) {
            throw new IllegalArgumentException("Configuration root must be a JSON object");
        }
        JsonObject root = rootValue.asObject();

        String modId = requiredString(root, "mod_id");
        String description = optionalString(root, "description", "");
        String input = requiredString(root, "input");
        String output = requiredString(root, "output");

        JsonValue weaponsValue = root.get("weapons");
        if (weaponsValue == null || !weaponsValue.isArray()) {
            throw new IllegalArgumentException("Configuration must contain a 'weapons' array");
        }
        JsonArray weapons = weaponsValue.asArray();
        if (weapons.size() == 0) {
            throw new IllegalArgumentException("'weapons' must contain at least one entry");
        }

        List<Change> changes = new ArrayList<>();
        Set<String> seenWeapons = new LinkedHashSet<>();
        Set<String> seenPairs = new LinkedHashSet<>();

        for (int i = 0; i < weapons.size(); i++) {
            JsonValue weaponValue = weapons.get(i);
            if (!weaponValue.isObject()) {
                throw new IllegalArgumentException("weapons[" + i + "] must be a JSON object");
            }
            JsonObject weapon = weaponValue.asObject();
            String id = requiredString(weapon, "id");
            if (id.isEmpty()) {
                throw new IllegalArgumentException("weapons[" + i + "].id must not be empty");
            }
            if (!seenWeapons.add(id)) {
                throw new IllegalArgumentException("weapon '" + id
                        + "' is declared more than once; each weapon may appear only once");
            }

            JsonValue changesValue = weapon.get("changes");
            if (changesValue == null || !changesValue.isObject()) {
                throw new IllegalArgumentException("weapons[" + i + "] ('" + id
                        + "') must contain a 'changes' object");
            }
            JsonObject changeObject = changesValue.asObject();
            if (changeObject.names().isEmpty()) {
                throw new IllegalArgumentException("weapons[" + i + "] ('" + id + "') has an empty 'changes' object");
            }

            for (String name : changeObject.names()) {
                WeaponField field = WeaponField.byName(name);
                if (field == null) {
                    String refused = WeaponField.refusedFields().get(name);
                    if (refused != null) {
                        throw new IllegalArgumentException("field '" + name + "' cannot be patched: " + refused);
                    }
                    throw new IllegalArgumentException("unknown field '" + name + "' for weapon '" + id
                            + "'; supported fields are " + supportedNames());
                }
                if (!seenPairs.add(id + "/" + name)) {
                    throw new IllegalArgumentException(
                            "weapon '" + id + "' modifies field '" + name + "' more than once");
                }
                JsonValue changeValue = changeObject.get(name);
                if (!changeValue.isObject()) {
                    throw new IllegalArgumentException("weapons[" + i + "].changes." + name
                            + " must be an object of the form {\"expected\": <number>, \"value\": <number>}");
                }
                JsonObject change = changeValue.asObject();
                for (String key : change.names()) {
                    if (!"expected".equals(key) && !"value".equals(key)) {
                        throw new IllegalArgumentException("weapons[" + i + "].changes." + name
                                + " has unknown member '" + key + "'; only 'expected' and 'value' are allowed");
                    }
                }
                double expected = requiredNumber(change, "expected", id, name);
                double value = requiredNumber(change, "value", id, name);
                String problem = field.validate(expected);
                if (problem != null) {
                    throw new IllegalArgumentException("weapons[" + i + "].changes." + name
                            + ".expected is invalid: " + problem);
                }
                problem = field.validate(value);
                if (problem != null) {
                    throw new IllegalArgumentException("weapons[" + i + "].changes." + name
                            + ".value is invalid: " + problem);
                }
                if (Double.compare(expected, value) == 0) {
                    throw new IllegalArgumentException("weapons[" + i + "].changes." + name
                            + " expects " + Avm2ConstantEditor.format(expected) + " and also sets the same value, "
                            + "so the change would be a no-op");
                }
                changes.add(new Change(id, field, expected, value));
            }
        }
        return new WeaponPackConfig(file, modId, description, input, output, changes);
    }

    public static String supportedNames() {
        StringBuilder builder = new StringBuilder();
        for (WeaponField field : WeaponField.all()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(field.name);
        }
        return builder.toString();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonValue value = object.get(key);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("Configuration member '" + key + "' must be a string");
        }
        String text = value.asString();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Configuration member '" + key + "' must not be empty");
        }
        return text;
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        JsonValue value = object.get(key);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("Configuration member '" + key + "' must be a string");
        }
        return value.asString();
    }

    private static double requiredNumber(JsonObject object, String key, String weaponId, String field) {
        JsonValue value = object.get(key);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("weapons." + weaponId + ".changes." + field + "." + key
                    + " must be a number");
        }
        return value.asDouble();
    }
}
