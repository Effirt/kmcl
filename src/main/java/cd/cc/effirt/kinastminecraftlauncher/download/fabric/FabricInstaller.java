package cd.cc.effirt.kinastminecraftlauncher.download.fabric;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.loader.Loaders;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricInstaller {

    private static final String META = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String META_AVAILABLE = "https://meta.fabricmc.net/v2/versions/game";

    public static Path install(String mcVersion, String requestedLoader, String instanceName)
            throws IOException {
        if (!VersionDownloader.exists(mcVersion)) {
            throw new VersionNotFoundException(mcVersion);
        }
        String loader = (requestedLoader == null || requestedLoader.isBlank())
                ? resolveLatestStableLoader(mcVersion)
                : requestedLoader.trim();

        Log.info("Fabric Loader version: {}", loader);

        JsonObject profile = fetchProfile(mcVersion, loader);
        JsonObject vanilla = VersionDownloader.fetchVanillaJson(mcVersion);
        JsonObject merged = merge(vanilla, profile, instanceName);

        Path target = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName)
                .resolve(mcVersion + ".json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, merged.toString(), StandardCharsets.UTF_8);

        Loaders.register(instanceName, mcVersion, "fabric", loader);
        return target;
    }

    /** Fabric Loader 是否支持该 Minecraft 版本。 */
    public static boolean exists(String mcVersion) {
        try {
            JsonArray games = parseArray(Http.get(META_AVAILABLE), "fabric game versions");
            for (JsonElement el : games) {
                if (el instanceof JsonObject o && o.has("version")
                        && o.get("version").getAsString().equals(mcVersion)) {
                    return true;
                }
            }
            return false;
        } catch (IOException _) {
            return true;   // 网络问题时不要直接否决
        }
    }

    /** 列出某 MC 版本可用的 Fabric Loader 版本（由新到旧）。 */
    public static java.util.List<String> listLoaders(String mcVersion) {
        try {
            JsonArray loaders = parseArray(Http.get(META + mcVersion), "fabric loader list " + mcVersion);
            java.util.List<String> out = new java.util.ArrayList<>();
            for (JsonElement el : loaders) {
                if (el instanceof JsonObject entry
                        && entry.get("loader") instanceof JsonObject loader
                        && loader.get("version") instanceof JsonPrimitive v) {
                    out.add(v.getAsString());
                }
            }
            return out;
        } catch (IOException e) {
            Log.warn("Failed to list Fabric loaders for {}: {}", mcVersion, e.getMessage());
            return java.util.List.of();
        }
    }

    // ---------- Loader 解析 ----------

    private static String resolveLatestStableLoader(String mcVersion) throws IOException {
        JsonArray loaders = parseArray(Http.get(META + mcVersion), "fabric loader list " + mcVersion);

        for (JsonElement el : loaders) {
            if (el instanceof JsonObject entry
                    && entry.get("loader") instanceof JsonObject loader
                    && loader.get("version") instanceof JsonPrimitive v
                    && loader.get("stable") instanceof JsonPrimitive s && s.getAsBoolean()) {
                return v.getAsString();
            }
        }
        if (!loaders.isEmpty()
                && loaders.get(0) instanceof JsonObject first
                && first.get("loader") instanceof JsonObject loader
                && loader.get("version") instanceof JsonPrimitive v) {
            return v.getAsString();
        }
        throw new IOException("No Fabric Loader for Minecraft " + mcVersion);
    }

    private static JsonObject fetchProfile(String mcVersion, String loader) throws IOException {
        return parseObject(
                Http.get(META + mcVersion + "/" + loader + "/profile/json"),
                "fabric profile " + mcVersion + "/" + loader);
    }

    private static JsonObject merge(JsonObject vanilla, JsonObject profile, String instanceName)
            throws IOException {
        if (vanilla.has("minecraftArguments") && !vanilla.has("arguments")) {
            throw new IOException("Fabric requires arguments-format version JSON "
                    + "(Minecraft 1.14+); got legacy minecraftArguments.");
        }

        JsonObject merged = vanilla.deepCopy();
        merged.addProperty("id", instanceName);

        if (profile.get("mainClass") instanceof JsonPrimitive mc) {
            merged.addProperty("mainClass", mc.getAsString());
        }

        JsonArray libs = ensureArray(merged, "libraries");
        if (profile.get("libraries") instanceof JsonArray plibs) {
            for (JsonElement e : plibs) libs.add(e);
        }

        if (profile.get("arguments") instanceof JsonObject pargs) {
            JsonObject margs = merged.get("arguments") instanceof JsonObject a
                    ? a : new JsonObject();
            merged.add("arguments", margs);
            if (pargs.get("jvm") instanceof JsonArray pjvm)
                for (JsonElement e : pjvm) ensureArray(margs, "jvm").add(e);
            if (pargs.get("game") instanceof JsonArray pgame)
                for (JsonElement e : pgame) ensureArray(margs, "game").add(e);
        }

        return merged;
    }

    private static JsonArray ensureArray(JsonObject parent, String key) {
        if (parent.get(key) instanceof JsonArray a) return a;
        JsonArray a = new JsonArray();
        parent.add(key, a);
        return a;
    }

    // ---------- 异常包装 ----------

    private static JsonObject parseObject(String json, String ctx) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("Malformed JSON from " + ctx, e);
        }
    }

    private static JsonArray parseArray(String json, String ctx) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonArray();
        } catch (JsonParseException e) {
            throw new IOException("Malformed JSON from " + ctx, e);
        }
    }
}