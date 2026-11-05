package cd.cc.effirt.kinastminecraftlauncher.instance;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InstanceRegistry {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private InstanceRegistry() {}

    public static Map<String, InstanceInfoRecord> loadAll() throws IOException {
        Path f = KMCLPath.getKmclPath().resolve("instances.json");
        if (!Files.exists(f)) return new LinkedHashMap<>();
        String json = Files.readString(f, StandardCharsets.UTF_8);
        if (json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, InstanceInfoRecord> map = GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, InstanceInfoRecord>>() {}.getType());
            return map == null ? new LinkedHashMap<>() : map;
        } catch (JsonParseException e) {
            throw new IOException("Malformed instances.json", e);
        }
    }

    public static InstanceInfoRecord get(String instanceName) throws IOException {
        return loadAll().get(instanceName);
    }

    public static void put(String instanceName, InstanceInfoRecord info) throws IOException {
        Map<String, InstanceInfoRecord> all = loadAll();
        all.put(instanceName, info);
        save(all);
    }

    public static void remove(String instanceName) throws IOException {
        Map<String, InstanceInfoRecord> all = loadAll();
        if (all.remove(instanceName) != null) save(all);
    }

    private static void save(Map<String, InstanceInfoRecord> all) throws IOException {
        Path f = KMCLPath.getKmclPath().resolve("instances.json");
        Files.createDirectories(f.getParent());
        Path temp = Files.createTempFile(f.getParent(), ".tmp-", ".json");
        try {
            Files.writeString(temp, GSON.toJson(all), StandardCharsets.UTF_8);
            try {
                Files.move(temp, f,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }
}