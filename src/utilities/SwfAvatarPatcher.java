import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.jpexs.decompiler.flash.tags.PlaceObject2Tag;
import com.jpexs.decompiler.flash.tags.PlaceObject3Tag;
import com.jpexs.decompiler.flash.tags.PlaceObjectTag;
import com.jpexs.decompiler.flash.tags.RemoveObject2Tag;
import com.jpexs.decompiler.flash.tags.RemoveObjectTag;
import com.jpexs.decompiler.flash.tags.ShowFrameTag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.tags.base.CharacterTag;
import com.jpexs.decompiler.flash.types.MATRIX;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only timeline inspector used while developing the medic avatar patch. */
public final class SwfAvatarPatcher {
    private record Placed(int characterId, MATRIX matrix) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "verify".equals(args[0])) {
            verify(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length != 4 || !"inspect".equals(args[0])) {
            throw new IllegalArgumentException("Usage: inspect <input.swf> <firstFrame> <lastFrame>"
                    + " or verify <original.swf> <patched.swf>");
        }
        Path input = Path.of(args[1]);
        int first = Integer.parseInt(args[2]);
        int last = Integer.parseInt(args[3]);
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(input.toFile()))) {
            SWF swf = new SWF(stream, false);
            CharacterTag character = swf.getCharacter(666);
            if (!(character instanceof DefineSpriteTag sprite)) {
                throw new IllegalStateException("Character 666 is not a DefineSprite");
            }
            Map<Integer, Placed> depths = new TreeMap<>();
            Map<Integer, Integer> uses = new TreeMap<>();
            int frame = 1;
            for (Tag tag : sprite.getTags()) {
                if (tag instanceof PlaceObject2Tag place) {
                    update(depths, place.depth, place.placeFlagHasCharacter, place.characterId,
                            place.placeFlagHasMatrix, place.matrix);
                } else if (tag instanceof PlaceObject3Tag place) {
                    update(depths, place.depth, place.placeFlagHasCharacter, place.characterId,
                            place.placeFlagHasMatrix, place.matrix);
                } else if (tag instanceof PlaceObjectTag place) {
                    depths.put(place.depth, new Placed(place.characterId, place.matrix));
                } else if (tag instanceof RemoveObject2Tag remove) {
                    depths.remove(remove.depth);
                } else if (tag instanceof RemoveObjectTag remove) {
                    depths.remove(remove.depth);
                } else if (tag instanceof ShowFrameTag) {
                    if (frame >= first && frame <= last) {
                        StringBuilder line = new StringBuilder("FRAME=").append(frame);
                        for (Map.Entry<Integer, Placed> entry : depths.entrySet()) {
                            Placed placed = entry.getValue();
                            line.append(" DEPTH=").append(entry.getKey())
                                    .append(" CHARACTER=").append(placed.characterId());
                            if (placed.matrix() != null) {
                                line.append(" TX=").append(placed.matrix().translateX)
                                        .append(" TY=").append(placed.matrix().translateY)
                                        .append(" SX=").append(placed.matrix().scaleX)
                                        .append(" SY=").append(placed.matrix().scaleY);
                            }
                            uses.merge(placed.characterId(), 1, Integer::sum);
                        }
                        System.out.println(line);
                    }
                    frame++;
                }
            }
            System.out.println("FRAME_COUNT=" + sprite.frameCount);
            for (Map.Entry<Integer, Integer> entry : uses.entrySet()) {
                CharacterTag used = swf.getCharacter(entry.getKey());
                System.out.println("USED_CHARACTER=" + entry.getKey() + " COUNT=" + entry.getValue()
                        + " TYPE=" + (used == null ? "missing" : used.getClass().getSimpleName()));
            }
        }
    }

    private static void verify(Path originalPath, Path patchedPath) throws Exception {
        try (BufferedInputStream originalStream = new BufferedInputStream(new FileInputStream(originalPath.toFile()));
             BufferedInputStream patchedStream = new BufferedInputStream(new FileInputStream(patchedPath.toFile()))) {
            SWF original = new SWF(originalStream, false);
            SWF patched = new SWF(patchedStream, false);
            Set<Integer> targets = Set.of(639, 640, 641, 642, 643);
            int compared = 0;
            int changedTargets = 0;
            for (Map.Entry<Integer, CharacterTag> entry : original.getCharacters(false).entrySet()) {
                int id = entry.getKey();
                CharacterTag after = patched.getCharacter(id);
                if (after == null) {
                    throw new IllegalStateException("Original character missing after patch: " + id);
                }
                boolean same = Arrays.equals(((Tag) entry.getValue()).getData(), ((Tag) after).getData());
                if (targets.contains(id)) {
                    if (same) {
                        throw new IllegalStateException("Target shape was not changed: " + id);
                    }
                    changedTargets++;
                } else {
                    if (!same) {
                        throw new IllegalStateException("Non-target character changed: " + id);
                    }
                    compared++;
                }
            }
            for (int id : targets) {
                CharacterTag character = patched.getCharacter(id);
                Set<Integer> needed = new HashSet<>();
                character.getNeededCharacters(needed, new HashSet<>(), patched);
                if (needed.size() != 1) {
                    throw new IllegalStateException("Target shape " + id
                            + " should reference exactly one bitmap, found " + needed);
                }
                int bitmapId = needed.iterator().next();
                System.out.println("TARGET_SHAPE=" + id + " BITMAP=" + bitmapId);
            }
            DefineSpriteTag sprite = (DefineSpriteTag) patched.getCharacter(666);
            Map<Integer, Integer> expected = Map.of(51, 639, 52, 640, 53, 641, 54, 642, 55, 643);
            Map<Integer, Integer> actual = frameCharacters(sprite, 51, 55);
            if (!actual.equals(expected)) {
                throw new IllegalStateException("Unexpected medic frame map: " + actual);
            }
            assertInstance((DefineSpriteTag) patched.getCharacter(669), "head", 666);
            assertInstance((DefineSpriteTag) patched.getCharacter(669), "headhold", 666);
            assertInstance((DefineSpriteTag) patched.getCharacter(905), "headhold", 666);
            int abcTags = compareAbcTags(original, patched);
            System.out.println("STATUS=OK");
            System.out.println("OUTPUT_REPARSED=true");
            System.out.println("TARGETS_CHANGED=" + changedTargets);
            System.out.println("NON_TARGET_CHARACTERS_IDENTICAL=" + compared);
            System.out.println("SPRITE_666_FRAME_MAP=" + actual);
            System.out.println("UNITMC_HEAD_AND_HEADHOLD=666");
            System.out.println("PROFILEUNIT_HEADHOLD=666");
            System.out.println("ABC_TAGS_IDENTICAL=" + abcTags);
            System.out.println("OUTPUT_SHA256=" + hex(MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(patchedPath))));
        }
    }

    private static void assertInstance(DefineSpriteTag sprite, String name, int characterId) {
        int matches = 0;
        for (Tag tag : sprite.getTags()) {
            if (tag instanceof PlaceObject2Tag place && name.equals(place.name)
                    && place.characterId == characterId) {
                matches++;
            } else if (tag instanceof PlaceObject3Tag place && name.equals(place.name)
                    && place.characterId == characterId) {
                matches++;
            }
        }
        if (matches != 1) {
            throw new IllegalStateException("Expected one " + name + " -> " + characterId
                    + " placement in sprite " + sprite.spriteId + ", found " + matches);
        }
    }

    private static int compareAbcTags(SWF original, SWF patched) {
        List<Tag> before = new ArrayList<>();
        List<Tag> after = new ArrayList<>();
        for (Tag tag : original.getTags()) {
            if (tag.getClass().getSimpleName().startsWith("DoABC")) {
                before.add(tag);
            }
        }
        for (Tag tag : patched.getTags()) {
            if (tag.getClass().getSimpleName().startsWith("DoABC")) {
                after.add(tag);
            }
        }
        if (before.size() != after.size()) {
            throw new IllegalStateException("ABC tag count changed");
        }
        for (int i = 0; i < before.size(); i++) {
            if (!Arrays.equals(before.get(i).getData(), after.get(i).getData())) {
                throw new IllegalStateException("ABC tag changed at index " + i);
            }
        }
        return before.size();
    }

    private static Map<Integer, Integer> frameCharacters(DefineSpriteTag sprite, int first, int last) {
        Map<Integer, Placed> depths = new TreeMap<>();
        Map<Integer, Integer> frames = new TreeMap<>();
        int frame = 1;
        for (Tag tag : sprite.getTags()) {
            if (tag instanceof PlaceObject2Tag place) {
                update(depths, place.depth, place.placeFlagHasCharacter, place.characterId,
                        place.placeFlagHasMatrix, place.matrix);
            } else if (tag instanceof PlaceObject3Tag place) {
                update(depths, place.depth, place.placeFlagHasCharacter, place.characterId,
                        place.placeFlagHasMatrix, place.matrix);
            } else if (tag instanceof PlaceObjectTag place) {
                depths.put(place.depth, new Placed(place.characterId, place.matrix));
            } else if (tag instanceof RemoveObject2Tag remove) {
                depths.remove(remove.depth);
            } else if (tag instanceof RemoveObjectTag remove) {
                depths.remove(remove.depth);
            } else if (tag instanceof ShowFrameTag) {
                if (frame >= first && frame <= last) {
                    Placed placed = depths.get(1);
                    frames.put(frame, placed == null ? -1 : placed.characterId());
                }
                frame++;
            }
        }
        return frames;
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static void update(Map<Integer, Placed> depths, int depth, boolean hasCharacter,
            int characterId, boolean hasMatrix, MATRIX matrix) {
        Placed previous = depths.get(depth);
        int nextCharacter = hasCharacter ? characterId : previous == null ? -1 : previous.characterId();
        MATRIX nextMatrix = hasMatrix ? matrix : previous == null ? null : previous.matrix();
        depths.put(depth, new Placed(nextCharacter, nextMatrix));
    }
}
