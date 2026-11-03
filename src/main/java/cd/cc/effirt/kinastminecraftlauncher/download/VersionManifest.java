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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // --- internals -----------------------------------------------------

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
        return collect(versions);
    }

    // --- internals -----------------------------------------------------

    /** Converts versions[] to String[][]; type==null means "no filter". */
    private static String[][] collect(JsonArray versions) {
        List<String[]> out = new ArrayList<>(versions.size());
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            String[] row = toArray(v);
            if (row != null) out.add(row);   // skip malformed entries
        }
        return out.toArray(new String[0][]);
    }

    /** 所有 type 属于 types 的版本，releaseTime 降序（保持 versions[] 顺序）。 */
    public static String[][] getAllTyped(Set<String> types) {
        if (types == null || types.isEmpty()) return new String[0][];
        JsonObject root = parsed();
        if (root == null) return null;
        JsonArray versions = root.getAsJsonArray("versions");
        if (versions == null) return null;

        List<String[]> out = new ArrayList<>(versions.size());
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!types.contains(str(v, "type"))) continue;
            String[] row = toArray(v);
            if (row != null) out.add(row);
        }
        return out.toArray(new String[0][]);
    }

    /** 每个 type 取 versions[] 中第一条（即 releaseTime 最新的那条）。 */
    public static String[][] getLatestTyped(Set<String> types) {
        if (types == null || types.isEmpty()) return new String[0][];
        JsonObject root = parsed();
        if (root == null) return null;
        JsonArray versions = root.getAsJsonArray("versions");
        if (versions == null) return null;

        Set<String> seen = new HashSet<>();
        List<String[]> out = new ArrayList<>();
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            String t = str(v, "type");
            if (t == null || !types.contains(t)) continue;
            if (!seen.add(t)) continue;
            String[] row = toArray(v);
            if (row != null) out.add(row);
            if (seen.size() == types.size()) break;
        }
        return out.toArray(new String[0][]);
    }
}