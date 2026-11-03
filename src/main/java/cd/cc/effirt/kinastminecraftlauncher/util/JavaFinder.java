package cd.cc.effirt.kinastminecraftlauncher.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class JavaFinder {

    public static String javaExeName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
    }

    /**
     * 找任意一个可用的 java 可执行文件。优先 JAVA_HOME，其次 PATH，
     * 最后才尝试 System.getProperty("java.home")（native-image 下为 null）。
     * <p>适合"只需要一个能跑的 java"的场景，如运行 NeoForge installer。
     */
    public static Path findAny() throws IOException {
        String name = javaExeName();

        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            Path p = Path.of(home, "bin", name);
            if (Files.isRegularFile(p)) return p;
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p)) return p;
            }
        }

        String sys = System.getProperty("java.home");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys, "bin", name);
            if (Files.isRegularFile(p)) return p;
        }

        throw new IOException("No 'java' executable found "
                + "(checked JAVA_HOME, PATH, java.home). "
                + "Please install a JDK or set JAVA_HOME.");
    }
}