package cd.cc.effirt.kinastminecraftlauncher.util;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class FilesUtil {

    /** 递归删除目录（或文件），静默忽略单个删除失败。 */
    public static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {}
            });
        }
    }
}