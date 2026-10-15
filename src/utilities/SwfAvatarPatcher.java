import com.jpexs.decompiler.flash.SWF;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Map;

/** Command-line entry point for Avatar Mod v0.3. */
public final class SwfAvatarPatcher {
    public static void main(String[] args) {
        try {
            if (args.length < 1) usage();
            switch (args[0].toLowerCase()) {
                case "build" -> {
                    require(args, 3);
                    Path root = Path.of(args[2]).toAbsolutePath().normalize();
                    AvatarConfig config = AvatarConfig.load(Path.of(args[1]).toAbsolutePath().normalize(), root);
                    AvatarMod.Outcome result = AvatarMod.build(config, root);
                    System.out.println("STATUS=OK");
                    System.out.println("MODE=build");
                    System.out.println("OUTPUT=" + result.output());
                    System.out.println("REPORT=" + result.report());
                    System.out.println("INPUT_SHA256=" + result.inputSha256());
                    System.out.println("OUTPUT_SHA256=" + result.outputSha256());
                    System.out.println("AVATAR_FRAMES=" + result.applied().size());
                    System.out.println("RUNTIME_STATUS=manual_test_required");
                }
                case "validate" -> {
                    require(args, 3);
                    Path root = Path.of(args[2]).toAbsolutePath().normalize();
                    AvatarConfig config = AvatarConfig.load(Path.of(args[1]).toAbsolutePath().normalize(), root);
                    AvatarMod.validate(config, root);
                    System.out.println("STATUS=OK");
                    System.out.println("MODE=validate");
                    System.out.println("RULES=" + config.rules.size());
                }
                case "verify" -> {
                    require(args, 3);
                    Path root = Path.of(args[2]).toAbsolutePath().normalize();
                    AvatarConfig config = AvatarConfig.load(Path.of(args[1]).toAbsolutePath().normalize(), root);
                    int count = AvatarMod.verifyExisting(config, root).size();
                    System.out.println("STATUS=OK");
                    System.out.println("MODE=verify");
                    System.out.println("AVATAR_FRAMES=" + count);
                    System.out.println("OUTPUT_SHA256=" + AvatarMod.sha256(config.output(root)));
                    System.out.println("RUNTIME_STATUS=manual_test_required");
                }
                case "mapping" -> {
                    require(args, 2);
                    try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(args[1]))) {
                        SWF swf = new SWF(input, false);
                        SwfAvatarMapper.validateOriginal(swf);
                        Map<Integer, Integer> frames = SwfAvatarMapper.readFrameShapes(swf);
                        System.out.println("CLASS\tSKIN\tFRAME\tSHAPE");
                        for (SwfAvatarMapper.ClassMap map : SwfAvatarMapper.all()) {
                            for (int skin = 1; skin <= 5; skin++) {
                                System.out.println(map.id() + "\t" + skin + "\t" + map.frame(skin)
                                        + "\t" + frames.get(map.frame(skin)));
                            }
                        }
                    }
                }
                default -> usage();
            }
        } catch (Throwable error) {
            System.err.println("STATUS=ERROR");
            System.err.println("ERROR=" + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void require(String[] args, int count) { if (args.length != count) usage(); }
    private static void usage() {
        throw new IllegalArgumentException("Usage: build|validate|verify <config.json> <project-root> or mapping <input.swf>");
    }
}
