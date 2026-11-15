package cd.cc.effirt.kinastminecraftlauncher.exception;

import java.io.IOException;

public class VersionNotFoundException extends IOException {

    private final String version;

    public VersionNotFoundException(String version) {
        super("Unknown Minecraft version: " + version);
        this.version = version;
    }

    public String version() {
        return version;
    }
}
