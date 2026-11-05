package cd.cc.effirt.kinastminecraftlauncher.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class JavaFinder {

    public static String javaExeName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
    }

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

        throw new IOException("No 'java' executable found ");
    }
}