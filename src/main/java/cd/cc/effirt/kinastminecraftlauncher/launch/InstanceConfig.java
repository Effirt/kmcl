package cd.cc.effirt.kinastminecraftlauncher.launch;

import cd.cc.effirt.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class InstanceConfig {
    private static final Properties p = new Properties();

    public static void load(Path kmclConfig) {
        Path config = kmclConfig.resolve("kmcl.properties");
        if (Files.exists(config)) {
            try (BufferedReader br = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                p.load(br);
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
                try (Writer w = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) {
                    p.store(w, null);
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
