package cd.cc.effirt.kinastminecraftlauncher.exception;

import cd.cc.effirt.kinastminecraftlauncher.MinecraftLoaderEnum;

import java.io.IOException;
import java.io.Serial;

public class UnsupportedLoaderException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final MinecraftLoaderEnum loader;

    public UnsupportedLoaderException(MinecraftLoaderEnum loader) {
        super("Unsupported loader for direct version-JSON download: " + loader);
        this.loader = loader;
    }

    public MinecraftLoaderEnum loader() {
        return loader;
    }
}