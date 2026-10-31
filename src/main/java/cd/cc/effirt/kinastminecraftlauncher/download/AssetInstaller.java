package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.kinastminecraftlauncher.util.DownloadBatch;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class AssetInstaller {

    private static final String OBJECT_URL = "https://resources.download.minecraft.net/";

    private AssetInstaller() {}

    public static void install(VersionJSONParser.VersionInfo info, int maxAttempts)
            throws IOException {
        installLogging(info, maxAttempts);
        installObjects(info, maxAttempts);
    }

    // ============ logging ============

    public static void installLogging(VersionJSONParser.VersionInfo info, int maxAttempts)
            throws IOException {
        var logging = info.loggingClient();
        if (logging == null || logging.file() == null || logging.file().url() == null) {
            return;   // 旧版本可能没有 logging
        }
        var file = logging.file();
        var dest = VersionInstaller.assetsDir().resolve("log_configs").resolve(file.id());
        Files.createDirectories(dest.getParent());

        if (Files.exists(dest) && (file.size() <= 0 || Files.size(dest) == file.size())) {
            return;
        }
        Files.deleteIfExists(dest);

        Log.info("Downloading log config '{}'...", file.id());
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (var pb = new ProgressBar()) {
                pb.setCustomText(file.id());
                pb.start();
                Http.getDownload(file.url(), dest, pb);
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(dest);
                Log.warn("Attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
                continue;
            }
            if (file.size() <= 0 || Files.size(dest) == file.size()) return;

            long actual = Files.size(dest);
            Files.deleteIfExists(dest);
            last = new IOException("log config size mismatch (want " + file.size()
                    + ", got " + actual + ")");
            Log.warn("Attempt {}/{}: {}", attempt, maxAttempts, last.getMessage());
        }
        throw last != null ? last : new IOException("Download failed: " + file.url());
    }

    // ============ index + objects ============

    private static void installObjects(VersionJSONParser.VersionInfo info, int maxAttempts)
            throws IOException {
        var ai = info.assetIndex();
        if (ai == null || ai.url() == null) {
            Log.warn("No assetIndex");
            return;
        }

        var indexFile = VersionInstaller.assetsDir().resolve("indexes").resolve(ai.id() + ".json");
        Files.createDirectories(indexFile.getParent());
        ensureIndex(ai, indexFile, maxAttempts);

        var objectsDir = VersionInstaller.assetsDir().resolve("objects");
        Files.createDirectories(objectsDir);

        var tasks = new LinkedHashMap<String, DownloadBatch.Task>();
        try (var r = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            var objects = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("objects");
            if (objects == null) {
                Log.warn("No 'objects' in asset index {}", ai.id());
                return;
            }
            for (var e : objects.entrySet()) {
                var o = e.getValue().getAsJsonObject();
                var hash = o.get("hash").getAsString();
                if (tasks.containsKey(hash)) continue;
                long size = o.has("size") ? o.get("size").getAsLong() : 0;
                var sub = hash.substring(0, 2);
                tasks.put(hash, new DownloadBatch.Task(
                        OBJECT_URL + sub + "/" + hash, size,
                        objectsDir.resolve(sub).resolve(hash)));
            }
        }

        Log.info("Installing {} asset objects from index '{}'...", tasks.size(), ai.id());
        DownloadBatch.run(new ArrayList<>(tasks.values()), maxAttempts, "assets");
    }

    private static void ensureIndex(VersionJSONParser.AssetIndex ai, Path target, int maxAttempts)
            throws IOException {
        if (Files.exists(target) && (ai.size() <= 0 || Files.size(target) == ai.size())) return;

        Log.info("Downloading asset index '{}'...", ai.id());
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (var pb = new ProgressBar()) {
                pb.setCustomText("index " + ai.id() + ".json");
                pb.start();
                Http.getDownload(ai.url(), target, pb);
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(target);
                Log.warn("Attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
                continue;
            }

            if (ai.size() <= 0 || Files.size(target) == ai.size()) return;

            long actual = Files.size(target);          //先读再删
            Files.deleteIfExists(target);
            last = new IOException("index size mismatch (want " + ai.size()
                    + ", got " + actual + ")");
            Log.warn("Attempt {}/{}: {}", attempt, maxAttempts, last.getMessage());
        }
        throw last != null ? last : new IOException("Download failed: " + ai.url());
    }
}