import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.tags.DefineBitsLossless2Tag;
import com.jpexs.decompiler.flash.tags.DefineShape4Tag;
import com.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.jpexs.decompiler.flash.tags.PlaceObject2Tag;
import com.jpexs.decompiler.flash.tags.RemoveObject2Tag;
import com.jpexs.decompiler.flash.tags.ShowFrameTag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.types.MATRIX;
import com.jpexs.decompiler.flash.types.RECT;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Imports PNG/JPEG bytes once and creates bitmap-filled shapes fitted to original head bounds. */
public final class SwfBitmapImporter {
    public record ImageAsset(int bitmapId, int width, int height, boolean sourceHasAlpha) {}
    public record FrameAsset(int frame, int shapeId, int bitmapId, RECT bounds) {}

    private final SWF swf;
    private final DefineSpriteTag headSprite;

    public SwfBitmapImporter(SWF swf) {
        this.swf = swf;
        this.headSprite = (DefineSpriteTag) swf.getCharacter(666);
    }

    public ImageAsset importImage(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        if (decoded == null) throw new IllegalArgumentException("Unsupported or corrupt image: " + path);
        boolean alpha = decoded.getColorModel().hasAlpha();
        DefineBitsLossless2Tag image = new DefineBitsLossless2Tag(swf);
        int id = swf.getNextCharacterId();
        image.setCharacterId(id);
        image.setImage(bytes);
        image.setModified(true);
        SWF.addTagBefore(image, headSprite);
        return new ImageAsset(id, decoded.getWidth(), decoded.getHeight(), alpha);
    }

    public FrameAsset installFrame(ImageAsset image, RECT originalBounds, int frame, int restoreShapeId) {
        DefineShape4Tag shape = new DefineShape4Tag(swf);
        int shapeId = swf.getNextCharacterId();
        shape.setCharacterId(shapeId);
        shape.shapeBounds = new RECT(originalBounds);
        shape.edgeBounds = new RECT(originalBounds);
        shape.shapes = ((DefineBitsLossless2Tag) swf.getCharacter(image.bitmapId()))
                .getShape(new RECT(originalBounds), true, image.bitmapId());
        shape.setModified(true);
        SWF.addTagBefore(shape, headSprite);

        insertReplacement(frame, shapeId);
        insertReplacement(frame + 1, restoreShapeId);
        headSprite.setModified(true);
        headSprite.clearReadOnlyListCache();
        headSprite.resetTimeline();
        return new FrameAsset(frame, shapeId, image.bitmapId(), new RECT(originalBounds));
    }

    private void insertReplacement(int frame, int shapeId) {
        headSprite.clearReadOnlyListCache();
        int currentFrame = 1;
        int insertAt = -1;
        for (Tag tag : headSprite.getTags()) {
            if (tag instanceof ShowFrameTag) {
                if (currentFrame == frame) {
                    insertAt = headSprite.indexOfTag(tag);
                    break;
                }
                currentFrame++;
            }
        }
        if (insertAt < 0) throw new IllegalStateException("Reserved head frame not found: " + frame);
        RemoveObject2Tag remove = new RemoveObject2Tag(swf);
        remove.depth = 1;
        PlaceObject2Tag place = new PlaceObject2Tag(swf, false, 1, shapeId,
                new MATRIX(), null, -1, null, -1, null);
        headSprite.addTag(insertAt++, remove);
        headSprite.addTag(insertAt, place);
    }
}
