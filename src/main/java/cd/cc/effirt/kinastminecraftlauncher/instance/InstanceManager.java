package cd.cc.effirt.kinastminecraftlauncher.instance;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.util.FilesUtil;
import cd.cc.effirt.auroralib.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class InstanceManager {

    public static void rename(String oldName, String newName) throws IOException {
        Log.info("Renaming '{}' to '{}'...", oldName, newName);
        if (oldName.equals(newName)) {
            Log.info("Nothing to rename");
            return;
        }
        if (newName == null || newName.isBlank() || newName.contains("/") || newName.contains("\\")
                || newName.equals(".") || newName.equals("..")) {
            throw new IOException("Invalid instance name: " + newName);
        }
        Map<String, InstanceInfoRecord> all = InstanceRegistry.loadAll();
        InstanceInfoRecord info = all.get(oldName);
        if (info == null) throw new IOException("Instance not found: " + oldName);
        if (all.containsKey(newName)) {
            throw new IOException("Instance already exists: " + newName);
        }

        Path oldVersionDir = KMCLPath.getKmclPath().resolve("versions").resolve(oldName);
        Path newVersionDir = KMCLPath.getKmclPath().resolve("versions").resolve(newName);
        Path oldGameDir = KMCLPath.getKmclPath().resolve("instances").resolve(oldName);
        Path newGameDir = KMCLPath.getKmclPath().resolve("instances").resolve(newName);

        if (Files.exists(newVersionDir) || Files.exists(newGameDir)) {
            throw new IOException("Target directory already exists: " + newName);
        }

        if (Files.exists(oldVersionDir)) {
            Files.createDirectories(newVersionDir.getParent());
            Files.move(oldVersionDir, newVersionDir);
        }
        if (Files.exists(oldGameDir)) {
            Files.createDirectories(newGameDir.getParent());
            Files.move(oldGameDir, newGameDir);
        }

        // version JSON 里的 id 保持与实例名一致
        Path jsonFile = newVersionDir.resolve(info.minecraftVersion() + ".json");
        if (Files.exists(jsonFile)) {
            try {
                JsonObject json = JsonParser.parseString(
                        Files.readString(jsonFile, StandardCharsets.UTF_8)).getAsJsonObject();
                json.addProperty("id", newName);
                Files.writeString(jsonFile, json.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.warn("Failed to update id in {}: {}", jsonFile, e.getMessage());
            }
        }

        InstanceRegistry.remove(oldName);
        InstanceRegistry.put(newName, info);
        Log.info("Successfully rename '{}' to '{}'...", oldName, newName);
    }

    public static void delete(String name, boolean deleteData) throws IOException {
        Log.info("Deleting '{}'...", name);
        Map<String, InstanceInfoRecord> all = InstanceRegistry.loadAll();
        if (!all.containsKey(name)) throw new IOException("Instance not found: " + name);

        Path versionDir = KMCLPath.getKmclPath().resolve("versions").resolve(name);
        if (Files.exists(versionDir)) {
            FilesUtil.deleteRecursively(versionDir);
        }

        if (deleteData) {
            Path gameDir = KMCLPath.getKmclPath().resolve("instances").resolve(name);
            if (Files.exists(gameDir)) {
                FilesUtil.deleteRecursively(gameDir);
            }
        }

        InstanceRegistry.remove(name);

        Log.info("Successfully deleted instance '{}'{}", name, deleteData ? " (data removed)" : "");
    }
}