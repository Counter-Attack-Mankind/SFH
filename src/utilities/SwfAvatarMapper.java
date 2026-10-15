import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.jpexs.decompiler.flash.tags.EndTag;
import com.jpexs.decompiler.flash.tags.PlaceObject2Tag;
import com.jpexs.decompiler.flash.tags.PlaceObject3Tag;
import com.jpexs.decompiler.flash.tags.PlaceObjectTag;
import com.jpexs.decompiler.flash.tags.RemoveObject2Tag;
import com.jpexs.decompiler.flash.tags.RemoveObjectTag;
import com.jpexs.decompiler.flash.tags.ShowFrameTag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.tags.base.ShapeTag;
import com.jpexs.decompiler.flash.types.RECT;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Verified class/skin/frame/shape mapping for the original SFH head sprite (character 666). */
public final class SwfAvatarMapper {
    public record ClassMap(String id, int classNumber, int startFrame, int[] expectedShapes) {
        public int frame(int skin) { return startFrame + skin; }
        public int expectedShape(int skin) { return expectedShapes[skin - 1]; }
    }

    private static final Map<String, ClassMap> CLASSES = new LinkedHashMap<>();
    static {
        CLASSES.put("medic", new ClassMap("medic", 1, 50, new int[]{639, 640, 641, 642, 643}));
        CLASSES.put("sniper", new ClassMap("sniper", 2, 0, new int[]{632, 633, 634, 635, 636}));
        CLASSES.put("soldier", new ClassMap("soldier", 3, 150, new int[]{657, 658, 659, 660, 661}));
        CLASSES.put("tank", new ClassMap("tank", 4, 100, new int[]{650, 651, 652, 653, 654}));
    }

    private SwfAvatarMapper() {}
    public static Set<String> classIds() { return CLASSES.keySet(); }
    public static ClassMap classMap(String id) { return CLASSES.get(id); }

    public static Map<Integer, Integer> readFrameShapes(SWF swf) {
        if (!(swf.getCharacter(666) instanceof DefineSpriteTag sprite) || sprite.frameCount < 200) {
            throw new IllegalStateException("Expected DefineSprite 666 with at least 200 frames");
        }
        Map<Integer, Integer> active = new TreeMap<>();
        Map<Integer, Integer> result = new TreeMap<>();
        int frame = 1;
        for (Tag tag : sprite.getTags()) {
            if (tag instanceof EndTag) break;
            if (tag instanceof PlaceObject2Tag p && p.placeFlagHasCharacter) active.put(p.depth, p.characterId);
            else if (tag instanceof PlaceObject3Tag p && p.placeFlagHasCharacter) active.put(p.depth, p.characterId);
            else if (tag instanceof PlaceObjectTag p) active.put(p.depth, p.characterId);
            else if (tag instanceof RemoveObject2Tag r) active.remove(r.depth);
            else if (tag instanceof RemoveObjectTag r) active.remove(r.depth);
            else if (tag instanceof ShowFrameTag) {
                result.put(frame++, active.getOrDefault(1, -1));
            }
        }
        if (result.size() != sprite.frameCount) {
            throw new IllegalStateException("Sprite 666 declares " + sprite.frameCount
                    + " frames but its executable timeline contains " + result.size());
        }
        return result;
    }

    public static void validateOriginal(SWF swf) {
        Map<Integer, Integer> frames = readFrameShapes(swf);
        for (ClassMap map : CLASSES.values()) {
            for (int skin = 1; skin <= 5; skin++) {
                int actual = frames.getOrDefault(map.frame(skin), -1);
                if (actual != map.expectedShape(skin)) {
                    throw new IllegalStateException(map.id + " skin " + skin + " expected frame "
                            + map.frame(skin) + " -> shape " + map.expectedShape(skin) + ", found " + actual);
                }
            }
        }
    }

    public static RECT originalBounds(SWF swf, String classId, int skin) {
        int id = classMap(classId).expectedShape(skin);
        if (!(swf.getCharacter(id) instanceof ShapeTag shape)) {
            throw new IllegalStateException("Expected shape character " + id);
        }
        return new RECT(shape.getRect());
    }

    public static List<ClassMap> all() { return List.copyOf(CLASSES.values()); }
}
