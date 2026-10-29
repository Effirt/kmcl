package cd.cc.effirt.kinastminecraftlauncher;

import java.nio.file.Path;

public class KMCLPath {

    public static Path getKmclPath() {
        return Path.of(System.getProperty("user.home")).resolve(".kmcl").toAbsolutePath().normalize();
    }
}
