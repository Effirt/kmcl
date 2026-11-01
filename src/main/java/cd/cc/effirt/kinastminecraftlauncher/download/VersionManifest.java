package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VersionManifest {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest.json";

    // Matches the server's Cache-Control: public, max-age=120
    private static final long CACHE_MS = 120_000L;

    // Field layout of the String[] returned by getLatest / getRelease / getSnapshot
    public static final int ID = 0;
    public static final int TYPE = 1;
    public static final int URL = 2;
    public static final int RELEASE_TIME = 4;

    private static JsonObject cache;
    private static long cacheAt;

    /**
     * Newest entry overall (versions[0], sorted by releaseTime desc).
     * In practice this is the latest snapshot.
     */
    public static String[] getLatest() {
        JsonObject root = parsed();
        if (root == null) return null;
        JsonArray versions = root.getAsJsonArray("versions");
        if (versions == null || versions.isEmpty()) return null;
        return toArray(versions.get(0).getAsJsonObject());
    }

    /** Latest stable release — the entry named by latest.release. */
    public static String[] getRelease() {
        return latestEntry("release");
    }

    /** Latest snapshot — the entry named by latest.snapshot. */
    public static String[] getSnapshot() {
        return latestEntry("snapshot");
    }

    // --- internals -----------------------------------------------------

    private static String[] latestEntry(String key) {
        JsonObject root = parsed();
        if (root == null) return null;

        String id;
        try {
            id = root.getAsJsonObject("latest").get(key).getAsString();
        } catch (RuntimeException e) {
            Log.error("Manifest missing latest." + key, e);
            return null;
        }

        for (JsonElement el : root.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            if (id.equals(v.get("id").getAsString())) return toArray(v);
        }
        return null;
    }

    private static String[] toArray(JsonObject v) {
        try {
            return new String[]{
                    str(v, "id"),
                    str(v, "type"),
                    str(v, "url"),
                    str(v, "time"),
                    str(v, "releaseTime"),
            };
        } catch (RuntimeException e) {
            Log.error("Malformed version entry", e);
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null ? null : el.getAsString();
    }

    private static synchronized JsonObject parsed() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < CACHE_MS) return cache;

        String body = fetchRaw();
        if (body == null) return null;

        try {
            cache = JsonParser.parseString(body).getAsJsonObject();
            cacheAt = now;
            return cache;
        } catch (RuntimeException e) {
            Log.error("Failed to parse version_manifest.json", e);
            return null;
        }
    }

    private static String fetchRaw() {
        try (ProgressBar pb = new ProgressBar()) {
            pb.setCustomText("version_manifest.json");
            pb.start();
            Log.info("Getting manifest...");
            return Http.get(MANIFEST_URL, pb);
        } catch (IOException e) {
            Log.error("Failed to get manifest", e);
            return null;
        }
    }

    /** Every entry in versions[], newest first. ~hundreds of rows. */
    public static String[][] getAll() {
        JsonObject root = parsed();
        if (root == null) return null;
        JsonArray versions = root.getAsJsonArray("versions");
        if (versions == null) return null;
        return collect(versions, null);
    }

    /** Every entry with type == "release", newest first. */
    public static String[][] getReleaseAll() {
        return byType("release");
    }

    /** Every entry with type == "snapshot", newest first. */
    public static String[][] getSnapshotAll() {
        return byType("snapshot");
    }

    // --- internals -----------------------------------------------------

    private static String[][] byType(String type) {
        JsonObject root = parsed();
        if (root == null) return null;
        JsonArray versions = root.getAsJsonArray("versions");
        if (versions == null) return null;
        return collect(versions, type);
    }

    /** Converts versions[] to String[][]; type==null means "no filter". */
    private static String[][] collect(JsonArray versions, String type) {
        List<String[]> out = new ArrayList<>(versions.size());
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (type != null && !type.equals(str(v, "type"))) continue;
            String[] row = toArray(v);
            if (row != null) out.add(row);   // skip malformed entries
        }
        return out.toArray(new String[0][]);
    }
}