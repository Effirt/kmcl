package cd.cc.effirt.kinastminecraftlauncher.instance.launch;

import cd.cc.effirt.auroralib.Log;
import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class InstanceConfig {
    private static final Properties p = new Properties();

    public static void load(String instanceName) {
        load(KMCLPath.getKmclPath().resolve("instances").resolve(instanceName));
    }

    public static void load(Path gameDir) {
        Path config = gameDir.resolve("kmcl.properties");
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
            p.setProperty("defaultJvmArguments", "true");
            try {
                Files.createDirectories(gameDir);
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

    public static boolean getDefaultJvmArguments() {
        String s = p.getProperty("defaultJvmArguments");
        return switch (s) {
            case "true" -> true;
            case "false" -> false;
            case null -> true;
            default -> {
                Log.warn("Invalid value '{}'", p.getProperty("defaultJvmArguments"));
                yield true;
            }
        };
    }

}
