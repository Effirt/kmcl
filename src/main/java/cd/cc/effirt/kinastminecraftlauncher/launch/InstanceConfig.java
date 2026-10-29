package cd.cc.effirt.kinastminecraftlauncher.launch;

import cd.cc.effirt.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class InstanceConfig {
    private static final Properties p = new Properties();

    public static void load(Path kmclConfig) {
        Path config = kmclConfig.resolve("kmcl.properties");
        if (Files.exists(config)) {
            try (InputStream is = Files.newInputStream(config)) {
                p.load(is);
            } catch (IOException e) {
                Log.error("Failed to read instance config", e);
            }
        } else {
            p.setProperty("java", "");
            p.setProperty("jvmArguments", "");
            p.setProperty("arguments", "");
            p.setProperty("minMemory", "");
            p.setProperty("maxMemory", "");
            try {
                try (OutputStream os = Files.newOutputStream(config)) {
                    p.store(os, null);
                }
            } catch (IOException e) {
                Log.error("Failed to write instance config", e);
            }
        }
    }

    public static String getJava() {
        return p.getProperty("java", null);
    }

    public static String getJvmArguments() {
        return p.getProperty("jvmArguments", null);
    }

    public static String getArguments() {
        return p.getProperty("arguments", null);
    }

    public static String getMinMemory() {
        return p.getProperty("minMemory", null);
    }

    public static String getMaxMemory() {
        return p.getProperty("maxMemory", null);
    }
}
