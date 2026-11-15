package cd.cc.effirt.kinastminecraftlauncher.exception;

import cd.cc.effirt.kinastminecraftlauncher.MinecraftLoaderEnum;

import java.io.IOException;

public class UnsupportedLoaderException extends IOException {

    private final MinecraftLoaderEnum loader;

    public UnsupportedLoaderException(MinecraftLoaderEnum loader) {
        super("Unsupported loader for direct version-JSON download: " + loader);
        this.loader = loader;
    }

    public MinecraftLoaderEnum loader() {
        return loader;
    }
}