import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict configuration model for the reusable v0.3 avatar mod. */
public final class AvatarConfig {
    public enum Side { LOCAL_PLAYER, ALLY, ENEMY, ALL }

    public static final class Rule {
        public final int index;
        public final Side side;
        public final String classId;
        public final List<Integer> skins;
        public final String imageText;

        Rule(int index, Side side, String classId, List<Integer> skins, String imageText) {
            this.index = index;
            this.side = side;
            this.classId = classId;
            this.skins = skins;
            this.imageText = imageText;
        }

        public Path image(Path root) {
            Path value = Path.of(imageText);
            return (value.isAbsolute() ? value : root.resolve(value)).normalize();
        }

        String key() { return side + "/" + classId + "/" + skins; }
    }

    public final Path file;
    public final String modId;
    public final String description;
    public final String inputText;
    public final String outputText;
    public final List<Rule> rules;

    private AvatarConfig(Path file, String modId, String description, String inputText,
            String outputText, List<Rule> rules) {
        this.file = file;
        this.modId = modId;
        this.description = description;
        this.inputText = inputText;
        this.outputText = outputText;
        this.rules = rules;
    }

    public Path input(Path root) { return resolve(root, inputText); }
    public Path output(Path root) { return resolve(root, outputText); }

    private static Path resolve(Path root, String text) {
        Path path = Path.of(text);
        return (path.isAbsolute() ? path : root.resolve(path)).normalize();
    }

    public static AvatarConfig load(Path file, Path root) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Avatar configuration does not exist: " + file);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<String> duplicates = JsonLint.findDuplicateMembers(text);
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Duplicate JSON members: " + String.join(", ", duplicates));
        }
        JsonValue parsed = Json.parse(text);
        if (!parsed.isObject()) throw new IllegalArgumentException("Configuration root must be an object");
        JsonObject object = parsed.asObject();
        only(object, Set.of("mod_id", "description", "input", "output", "avatars"), "$ ");
        String modId = string(object, "mod_id", true);
        String description = string(object, "description", false);
        String input = string(object, "input", true);
        String output = string(object, "output", true);
        JsonValue avatarValue = object.get("avatars");
        if (avatarValue == null || !avatarValue.isArray() || avatarValue.asArray().size() == 0) {
            throw new IllegalArgumentException("'avatars' must be a non-empty array");
        }
        List<Rule> rules = new ArrayList<>();
        JsonArray avatars = avatarValue.asArray();
        for (int i = 0; i < avatars.size(); i++) {
            if (!avatars.get(i).isObject()) throw new IllegalArgumentException("avatars[" + i + "] must be an object");
            JsonObject entry = avatars.get(i).asObject();
            only(entry, Set.of("target", "image"), "avatars[" + i + "]");
            JsonValue targetValue = entry.get("target");
            if (targetValue == null || !targetValue.isObject()) {
                throw new IllegalArgumentException("avatars[" + i + "].target must be an object");
            }
            JsonObject target = targetValue.asObject();
            only(target, Set.of("side", "class", "skins"), "avatars[" + i + "].target");
            Side side;
            try {
                side = Side.valueOf(string(target, "side", true).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("avatars[" + i + "].target.side must be local_player, ally, enemy, or all");
            }
            String classId = string(target, "class", true).toLowerCase(Locale.ROOT);
            if (!SwfAvatarMapper.classIds().contains(classId)) {
                throw new IllegalArgumentException("avatars[" + i + "].target.class must be medic, sniper, soldier, or tank");
            }
            List<Integer> skins = parseSkins(target.get("skins"), i);
            String image = string(entry, "image", true);
            String lower = image.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                throw new IllegalArgumentException("avatars[" + i + "].image must be PNG, JPG, or JPEG");
            }
            Rule rule = new Rule(i, side, classId, skins, image);
            Path imagePath = rule.image(root);
            if (!Files.isRegularFile(imagePath)) throw new IllegalArgumentException("Image does not exist: " + imagePath);
            rules.add(rule);
        }
        rejectConflicts(rules);
        AvatarConfig config = new AvatarConfig(file, modId, description, input, output, List.copyOf(rules));
        validatePaths(config, root);
        return config;
    }

    private static List<Integer> parseSkins(JsonValue value, int index) {
        if (value != null && value.isString() && "all".equalsIgnoreCase(value.asString())) {
            return List.of(1, 2, 3, 4, 5);
        }
        if (value == null || !value.isArray() || value.asArray().size() == 0) {
            throw new IllegalArgumentException("avatars[" + index + "].target.skins must be 'all' or a non-empty array");
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (JsonValue item : value.asArray()) {
            if (!item.isNumber()) throw new IllegalArgumentException("skins entries must be integers 1..5");
            int skin = item.asInt();
            if (skin < 1 || skin > 5 || !result.add(skin)) {
                throw new IllegalArgumentException("skins entries must be unique integers 1..5");
            }
        }
        return List.copyOf(result);
    }

    private static void rejectConflicts(List<Rule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                Rule a = rules.get(i), b = rules.get(j);
                if (!a.classId.equals(b.classId) || disjoint(a.skins, b.skins)) continue;
                EnumSet<Side> aa = identities(a.side), bb = identities(b.side);
                aa.retainAll(bb);
                if (!aa.isEmpty()) {
                    throw new IllegalArgumentException("Avatar rules " + a.index + " and " + b.index
                            + " overlap for " + a.classId + " skins " + a.skins + "/" + b.skins);
                }
            }
        }
    }

    private static EnumSet<Side> identities(Side side) {
        return side == Side.ALL ? EnumSet.of(Side.LOCAL_PLAYER, Side.ALLY, Side.ENEMY) : EnumSet.of(side);
    }

    private static boolean disjoint(List<Integer> a, List<Integer> b) {
        for (int value : a) if (b.contains(value)) return false;
        return true;
    }

    private static void validatePaths(AvatarConfig config, Path root) {
        Path input = config.input(root).toAbsolutePath().normalize();
        Path output = config.output(root).toAbsolutePath().normalize();
        Path builds = root.resolve("builds").toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("Input SWF does not exist: " + input);
        if (input.equals(output)) throw new IllegalArgumentException("Output must not overwrite input");
        if (!output.startsWith(builds) || output.equals(builds)) {
            throw new IllegalArgumentException("Output must stay inside builds/: " + output);
        }
    }

    private static String string(JsonObject object, String name, boolean required) {
        JsonValue value = object.get(name);
        if (value == null && !required) return "";
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("'" + name + "' must be a non-empty string");
        }
        return value.asString();
    }

    private static void only(JsonObject object, Set<String> allowed, String path) {
        for (String name : object.names()) {
            if (!allowed.contains(name)) throw new IllegalArgumentException(path + " has unknown member '" + name + "'");
        }
    }
}
