import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.ScriptPack;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.AVM2ConstantPool;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.avm2.instructions.InstructionDefinition;
import com.jpexs.decompiler.flash.abc.types.MethodBody;
import com.jpexs.decompiler.flash.abc.types.Multiname;
import com.jpexs.decompiler.flash.abc.types.Namespace;
import com.jpexs.decompiler.flash.abc.types.traits.Trait;
import com.jpexs.decompiler.flash.abc.types.traits.TraitMethodGetterSetter;
import com.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.tags.base.CharacterTag;
import com.jpexs.decompiler.flash.tags.base.ShapeTag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transactional resource + AVM2 implementation of the reusable Avatar Mod. */
public final class AvatarMod {
    private static final List<Integer> CUSTOM_FRAMES = customFramePool();
    public record Applied(AvatarConfig.Rule rule, int skin, int originalFrame,
                          SwfBitmapImporter.FrameAsset asset) {}
    public record Outcome(Path output, Path report, String inputSha256, String outputSha256,
                          List<Applied> applied) {}
    private record MethodRef(ABC abc, int bodyIndex, MethodBody body, AVM2Code code) {}
    private record Branch(AVM2Instruction instruction, AVM2Instruction target) {}

    private AvatarMod() {}

    public static void validate(AvatarConfig config, Path root) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(config.input(root).toFile()))) {
            SWF swf = new SWF(input, false);
            SwfAvatarMapper.validateOriginal(swf);
            locateSetSkin(swf);
            for (AvatarConfig.Rule rule : config.rules) {
                // Decode now, not after the SWF has been modified.
                if (javax.imageio.ImageIO.read(rule.image(root).toFile()) == null) {
                    throw new IllegalArgumentException("Unsupported or corrupt image: " + rule.image(root));
                }
            }
        }
    }

    public static Outcome build(AvatarConfig config, Path root) throws Exception {
        validate(config, root);
        Path inputPath = config.input(root).toAbsolutePath().normalize();
        Path outputPath = config.output(root).toAbsolutePath().normalize();
        Path reportPath = Path.of(outputPath + ".build.json");
        Path partial = outputPath.resolveSibling(outputPath.getFileName() + ".partial");
        Files.createDirectories(outputPath.getParent());
        Files.deleteIfExists(partial);
        String inputHash = sha256(inputPath);
        List<Applied> applied = new ArrayList<>();

        try {
            SWF swf;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(inputPath.toFile()))) {
                swf = new SWF(input, false);
            }
            SwfAvatarMapper.validateOriginal(swf);
            SwfBitmapImporter importer = new SwfBitmapImporter(swf);
            Map<Integer, Integer> originalFrames = SwfAvatarMapper.readFrameShapes(swf);
            int frameSlot = 0;
            for (AvatarConfig.Rule rule : config.rules) {
                SwfBitmapImporter.ImageAsset image = importer.importImage(rule.image(root));
                for (int skin : rule.skins) {
                    int originalFrame = SwfAvatarMapper.classMap(rule.classId).frame(skin);
                    if (frameSlot >= CUSTOM_FRAMES.size()) throw new IllegalArgumentException("Too many avatar skin targets");
                    int customFrame = CUSTOM_FRAMES.get(frameSlot++);
                    SwfBitmapImporter.FrameAsset asset = importer.installFrame(image,
                            SwfAvatarMapper.originalBounds(swf, rule.classId, skin), customFrame,
                            originalFrames.get(customFrame + 1));
                    applied.add(new Applied(rule, skin, originalFrame, asset));
                }
            }
            injectRules(swf, applied);
            try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(partial.toFile()))) {
                swf.saveTo(output);
            }
            verify(config, root, inputPath, partial, applied);
            publish(partial, outputPath);
            String outputHash = sha256(outputPath);
            writeReport(config, root, reportPath, inputHash, outputHash, applied);
            return new Outcome(outputPath, reportPath, inputHash, outputHash, List.copyOf(applied));
        } catch (Throwable error) {
            Files.deleteIfExists(partial);
            throw error;
        }
    }

    public static List<Applied> verifyExisting(AvatarConfig config, Path root) throws Exception {
        Path output = config.output(root).toAbsolutePath().normalize();
        if (!Files.isRegularFile(output)) throw new IllegalArgumentException("Built SWF does not exist: " + output);
        // Reconstruct expected ordering deterministically: frames begin after the original 200.
        List<Applied> expected = new ArrayList<>();
        int frameSlot = 0;
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(output.toFile()))) {
            SWF swf = new SWF(stream, false);
            Map<Integer, Integer> frames = SwfAvatarMapper.readFrameShapes(swf);
            for (AvatarConfig.Rule rule : config.rules) {
                for (int skin : rule.skins) {
                    int frame = CUSTOM_FRAMES.get(frameSlot++);
                    int shapeId = frames.getOrDefault(frame, -1);
                    expected.add(new Applied(rule, skin, SwfAvatarMapper.classMap(rule.classId).frame(skin),
                            new SwfBitmapImporter.FrameAsset(frame, shapeId, -1,
                                    SwfAvatarMapper.originalBounds(swf, rule.classId, skin))));
                    frame++;
                }
            }
        }
        verify(config, root, config.input(root), output, expected);
        return expected;
    }

    private static void injectRules(SWF swf, List<Applied> applied) throws Exception {
        MethodRef method = locateSetSkin(swf);
        AVM2Code code = method.code;
        AVM2ConstantPool pool = method.abc.constants;
        int insertion = locateHeadSetPart(code, pool) + 1;
        int unit = publicQName(pool, "unit");
        int human = publicQName(pool, "human");
        int team = publicQName(pool, "team");
        int game = publicQName(pool, "game");
        int player = publicQName(pool, "player");
        int head = findMultiname(pool, "head");
        int setPart = findMultiname(pool, "setPart");
        List<AVM2Instruction> injected = new ArrayList<>();
        List<Branch> branches = new ArrayList<>();

        for (Applied item : applied) {
            AVM2Instruction end = ins("nop");
            // Exact original class+skin frame. This remains valid after respawn and class changes.
            injected.add(ins("getlocal1"));
            injected.add(number(item.originalFrame));
            AVM2Instruction frameMismatch = ins("ifstrictne", 0);
            injected.add(frameMismatch);
            branches.add(new Branch(frameMismatch, end));
            appendSideCondition(injected, branches, item.rule.side, unit, human, team, game, player, end);
            injected.add(ins("getlocal0"));
            injected.add(ins("getlocal0"));
            injected.add(ins("getproperty", head));
            injected.add(number(item.asset.frame()));
            injected.add(ins("callpropvoid", setPart, 2));
            injected.add(end);
        }
        for (int i = 0; i < injected.size(); i++) {
            code.insertInstruction(insertion + i, injected.get(i), method.body);
        }
        code.markOffsets();
        for (Branch branch : branches) {
            int offset = (int) (branch.target.getAddress()
                    - branch.instruction.getAddress() - branch.instruction.getBytesLength());
            branch.instruction.setTargetOffset(offset);
        }
        code.markOffsets();
        method.body.max_stack = Math.max(method.body.max_stack, 4);
        method.body.setModified();
        ((Tag) method.abc.parentTag).setModified(true);
    }

    private static void appendSideCondition(List<AVM2Instruction> out, List<Branch> branches,
            AvatarConfig.Side side, int unit, int human, int team, int game, int player,
            AVM2Instruction end) {
        if (side == AvatarConfig.Side.ALL) return;
        // local_player is the Unit.human flag, not team membership.
        out.add(ins("getlocal0")); out.add(ins("getproperty", unit)); out.add(ins("getproperty", human));
        AVM2Instruction humanBranch = ins(side == AvatarConfig.Side.LOCAL_PLAYER ? "iffalse" : "iftrue", 0);
        out.add(humanBranch); branches.add(new Branch(humanBranch, end));
        if (side == AvatarConfig.Side.LOCAL_PLAYER) return;

        if (side == AvatarConfig.Side.ALLY) {
            // team 0 is free-for-all: non-player units there are enemies, never allies.
            addUnitProperty(out, unit, team); out.add(number(0));
            AVM2Instruction ffa = ins("ifstricteq", 0); out.add(ffa); branches.add(new Branch(ffa, end));
            addUnitProperty(out, unit, team);
            addPlayerTeam(out, unit, game, player, team);
            AVM2Instruction different = ins("ifstrictne", 0); out.add(different); branches.add(new Branch(different, end));
            return;
        }

        // enemy: every non-player unit in FFA, or a unit whose team differs from the player's.
        AVM2Instruction sideOk = ins("nop");
        addUnitProperty(out, unit, team); out.add(number(0));
        AVM2Instruction ffaOk = ins("ifstricteq", 0); out.add(ffaOk); branches.add(new Branch(ffaOk, sideOk));
        addUnitProperty(out, unit, team);
        addPlayerTeam(out, unit, game, player, team);
        AVM2Instruction sameTeam = ins("ifstricteq", 0); out.add(sameTeam); branches.add(new Branch(sameTeam, end));
        out.add(sideOk);
    }

    private static void addUnitProperty(List<AVM2Instruction> out, int unit, int property) {
        out.add(ins("getlocal0")); out.add(ins("getproperty", unit)); out.add(ins("getproperty", property));
    }

    private static void addPlayerTeam(List<AVM2Instruction> out, int unit, int game, int player, int team) {
        out.add(ins("getlocal0")); out.add(ins("getproperty", unit)); out.add(ins("getproperty", game));
        out.add(ins("getproperty", player)); out.add(ins("getproperty", team));
    }

    private static int locateHeadSetPart(AVM2Code code, AVM2ConstantPool pool) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 5; i < code.code.size(); i++) {
            AVM2Instruction call = code.code.get(i);
            if (!"callpropvoid".equals(call.definition.instructionName) || call.operands.length != 2
                    || call.operands[1] != 2 || !"setPart".equals(name(pool, call.operands[0]))) continue;
            boolean head = false, curSkin = false;
            for (int j = Math.max(0, i - 16); j < i; j++) {
                AVM2Instruction candidate = code.code.get(j);
                if ("getproperty".equals(candidate.definition.instructionName)) {
                    String property = name(pool, candidate.operands[0]);
                    head |= "head".equals(property); curSkin |= "curSkin".equals(property);
                }
            }
            if (head && curSkin) matches.add(i);
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected one UnitMC head setPart call, found " + matches);
        return matches.get(0);
    }

    private static MethodRef locateSetSkin(SWF swf) {
        List<ScriptPack> packs = new ArrayList<>();
        for (ScriptPack pack : swf.getAS3Packs()) if ("UnitMC".equals(pack.getPathScriptName())) packs.add(pack);
        if (packs.size() != 1) throw new IllegalStateException("Expected one UnitMC class, found " + packs.size());
        ABC abc = packs.get(0).abc;
        int classIndex = abc.findClassByName("UnitMC");
        List<Integer> bodies = new ArrayList<>();
        for (Trait trait : abc.instance_info.get(classIndex).instance_traits.traits) {
            if (trait instanceof TraitMethodGetterSetter method
                    && "setSkin".equals(name(abc.constants, trait.name_index))) {
                bodies.add(abc.findBodyIndex(method.method_info));
            }
        }
        if (bodies.size() != 1 || bodies.get(0) < 0) throw new IllegalStateException("UnitMC.setSkin was not uniquely resolved");
        MethodBody body = abc.bodies.get(bodies.get(0));
        return new MethodRef(abc, bodies.get(0), body, body.getCode());
    }

    private static int publicQName(AVM2ConstantPool pool, String property) {
        int namespace = -1;
        for (int i = 1; i < pool.getNamespaceCount(); i++) {
            Namespace candidate = pool.getNamespace(i);
            if (candidate.kind == Namespace.KIND_PACKAGE && candidate.getRawName(pool).isEmpty()) {
                namespace = i; break;
            }
        }
        if (namespace < 0) throw new IllegalStateException("Public namespace not found");
        int string = pool.addString(property);
        return pool.addMultiname(Multiname.createQName(false, string, namespace));
    }

    private static int findMultiname(AVM2ConstantPool pool, String wanted) {
        for (int i = 1; i < pool.getMultinameCount(); i++) if (wanted.equals(name(pool, i))) return i;
        throw new IllegalStateException("Multiname not found: " + wanted);
    }

    private static String name(AVM2ConstantPool pool, int index) {
        Multiname multiname = pool.getMultiname(index);
        return multiname != null && multiname.hasOwnName() ? pool.getString(multiname.name_index) : "";
    }

    private static AVM2Instruction number(int value) {
        return value <= 127 ? ins("pushbyte", value) : ins("pushshort", value);
    }

    private static AVM2Instruction ins(String name, int... operands) {
        InstructionDefinition definition = Avm2ConstantEditor.definition(name);
        return new AVM2Instruction(0, definition, operands);
    }

    private static void verify(AvatarConfig config, Path root, Path originalPath, Path outputPath,
            List<Applied> expected) throws Exception {
        SWF original, output;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(originalPath.toFile()))) {
            original = new SWF(in, false);
        }
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(outputPath.toFile()))) {
            output = new SWF(in, false);
        }
        SwfAvatarMapper.validateOriginal(output);
        Map<Integer, Integer> frames = SwfAvatarMapper.readFrameShapes(output);
        Set<Integer> newShapes = new HashSet<>();
        for (Applied item : expected) {
            int actualShape = frames.getOrDefault(item.asset.frame(), -1);
            if (actualShape < 0) throw new IllegalStateException("Missing custom avatar frame " + item.asset.frame());
            newShapes.add(actualShape);
            if (!(output.getCharacter(actualShape) instanceof ShapeTag shape)) {
                throw new IllegalStateException("Custom frame is not backed by a Shape: " + item.asset.frame());
            }
            Set<Integer> needed = new HashSet<>();
            shape.getNeededCharacters(needed, new HashSet<>(), output);
            if (needed.size() != 1) throw new IllegalStateException("Custom shape must reference exactly one bitmap");
        }
        int unchanged = 0;
        for (Map.Entry<Integer, CharacterTag> entry : original.getCharacters(false).entrySet()) {
            if (entry.getKey() == 666) continue;
            CharacterTag after = output.getCharacter(entry.getKey());
            if (after == null || !Arrays.equals(((Tag) entry.getValue()).getData(), ((Tag) after).getData())) {
                throw new IllegalStateException("Unexpected original character change: " + entry.getKey());
            }
            unchanged++;
        }
        MethodRef beforeMethod = locateSetSkin(original), afterMethod = locateSetSkin(output);
        if (Arrays.equals(beforeMethod.body.getCodeBytes(), afterMethod.body.getCodeBytes())) {
            throw new IllegalStateException("UnitMC.setSkin was not modified");
        }
        verifySelector(afterMethod, expected);
        if (beforeMethod.abc.bodies.size() != afterMethod.abc.bodies.size()) {
            throw new IllegalStateException("AVM2 method-body count changed");
        }
        for (int i = 0; i < beforeMethod.abc.bodies.size(); i++) {
            if (i == beforeMethod.bodyIndex) continue;
            if (!Arrays.equals(beforeMethod.abc.bodies.get(i).getCodeBytes(),
                    afterMethod.abc.bodies.get(i).getCodeBytes())) {
                throw new IllegalStateException("Unexpected AVM2 method change at body " + i);
            }
        }
        compareWeapons(original, output);
        if (!sha256(originalPath).equals(sha256(config.input(root)))) {
            throw new IllegalStateException("Configured original changed during build");
        }
        System.out.println("OUTPUT_REPARSED=true");
        System.out.println("ORIGINAL_CHARACTERS_IDENTICAL=" + unchanged);
        System.out.println("CUSTOM_FRAMES=" + expected.size());
        System.out.println("UNITMC_INSTANCE_SELECTOR=true");
        System.out.println("WEAPON_REGISTRY_IDENTICAL=true");
    }

    private static void verifySelector(MethodRef method, List<Applied> expected) {
        AVM2ConstantPool pool = method.abc.constants;
        for (Applied item : expected) {
            int matches = 0;
            for (int i = 0; i + 1 < method.code.code.size(); i++) {
                Double value = Avm2ConstantEditor.readConstant(pool, method.code.code.get(i));
                AVM2Instruction next = method.code.code.get(i + 1);
                if (value != null && value.intValue() == item.asset.frame()
                        && "callpropvoid".equals(next.definition.instructionName)
                        && "setPart".equals(name(pool, next.operands[0]))) matches++;
            }
            if (matches != 1) throw new IllegalStateException("Custom frame " + item.asset.frame()
                    + " is not selected exactly once in UnitMC.setSkin");
        }
        Set<String> properties = new HashSet<>();
        for (AVM2Instruction instruction : method.code.code) {
            if ("getproperty".equals(instruction.definition.instructionName)) {
                properties.add(name(pool, instruction.operands[0]));
            }
        }
        for (Applied item : expected) {
            if (item.rule.side != AvatarConfig.Side.ALL && !properties.contains("human")) {
                throw new IllegalStateException("Identity selector does not read Unit.human");
            }
            if ((item.rule.side == AvatarConfig.Side.ALLY || item.rule.side == AvatarConfig.Side.ENEMY)
                    && (!properties.contains("team") || !properties.contains("game") || !properties.contains("player"))) {
                throw new IllegalStateException("Team selector is incomplete");
            }
        }
    }

    private static void compareWeapons(SWF before, SWF after) throws Exception {
        WeaponRegistry.Resolution a = WeaponRegistry.resolve(before), b = WeaponRegistry.resolve(after);
        if (!a.byId.keySet().equals(b.byId.keySet())) throw new IllegalStateException("Weapon registry ids changed");
        for (String id : a.byId.keySet()) {
            WeaponRegistry.Registration aa = a.byId.get(id), bb = b.byId.get(id);
            for (int i = 0; i < WeaponRegistry.ARG_COUNT; i++) {
                if (!aa.args[i].display().equals(bb.args[i].display())) {
                    throw new IllegalStateException("Weapon registry changed at " + id + " argument " + (i + 1));
                }
            }
        }
    }

    private static void publish(Path partial, Path output) throws Exception {
        try {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Integer> customFramePool() {
        List<Integer> frames = new ArrayList<>();
        // Legal skins are 1..5. These middle-of-block frames cannot be reached by validated config,
        // team changes, quickmatch bot generation, or the menu skin selector.
        for (int start : new int[]{20, 70, 120, 170}) {
            for (int frame = start; frame < start + 30; frame++) frames.add(frame);
        }
        return List.copyOf(frames);
    }

    private static void writeReport(AvatarConfig config, Path root, Path report, String inputHash,
            String outputHash, List<Applied> applied) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"status\": \"static_verified\",\n  \"runtime_status\": \"manual_test_required\",");
        json.append("\n  \"mod_id\": \"").append(escape(config.modId)).append("\",");
        json.append("\n  \"input_sha256\": \"").append(inputHash).append("\",");
        json.append("\n  \"output_sha256\": \"").append(outputHash).append("\",");
        json.append("\n  \"built_at\": \"").append(OffsetDateTime.now()).append("\",");
        json.append("\n  \"rules\": [");
        for (int i = 0; i < applied.size(); i++) {
            Applied a = applied.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"side\": \"").append(a.rule.side.name().toLowerCase())
                    .append("\", \"class\": \"").append(a.rule.classId).append("\", \"skin\": ")
                    .append(a.skin).append(", \"original_frame\": ").append(a.originalFrame)
                    .append(", \"custom_frame\": ").append(a.asset.frame())
                    .append(", \"shape_id\": ").append(a.asset.shapeId()).append('}');
        }
        json.append("\n  ]\n}\n");
        Files.writeString(report, json.toString(), StandardCharsets.UTF_8);
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[1024 * 1024]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }
}
