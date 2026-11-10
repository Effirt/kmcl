package cd.cc.effirt.kinastminecraftlauncher.exception;

import java.io.IOException;
import java.io.Serial;

public class VersionNotFoundException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String version;

    public VersionNotFoundException(String version) {
        super("Unknown Minecraft version: " + version);
        this.version = version;
    }

    public String version() {
        return version;
    }
}
