package cd.cc.effirt.kinastminecraftlauncher;

import cd.cc.effirt.auroralib.Log;

import java.nio.file.Path;

public class KMCLPath {
    private static final String KMCL_PATH = System.getenv("KMCL_PATH");

    public static Path getKmclPath() {
        Path p;
        if (KMCL_PATH == null) {
            p = Path.of(System.getProperty("user.home")).resolve(".kmcl").toAbsolutePath().normalize();
        } else {
            p = Path.of(KMCL_PATH).resolve(".kmcl").toAbsolutePath().normalize();
        }
        Log.debug("Kmcl path in '{}'", p);
        return p;
    }
}
