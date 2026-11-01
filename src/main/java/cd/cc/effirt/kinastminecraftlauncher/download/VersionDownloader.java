package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class VersionDownloader {

    public static Path downloadVanillaJson(String mcVersion, String name, boolean isBase)
            throws IOException {
        String url = resolveUrl(mcVersion);
        if (url == null) throw new VersionNotFoundException(mcVersion);

        Path target = VersionInstaller.versionJson(name, mcVersion);
        Files.createDirectories(target.getParent());

        Log.info("Downloading {} version JSON...", mcVersion);
        try (var pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".json");
            pb.start();
            Http.getDownload(url, target, pb);
        }

        // 写 kmcl.minecraftVersion + kmcl.base
        try {
            var root = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            root.addProperty("kmcl.minecraftVersion", mcVersion);
            root.addProperty("kmcl.base", isBase);
            Files.writeString(target, root.toString(), StandardCharsets.UTF_8);
        } catch (JsonParseException e) {
            throw new IOException("Malformed version JSON from " + url, e);
        }

        // base JSON：供 ensureVanillaInstalled / NeoForgeInstaller 使用
        Path base = VersionInstaller.versionJson(mcVersion, mcVersion);
        if (!base.equals(target) && !Files.exists(base)) {
            try {
                var root = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                root.addProperty("kmcl.base", true);
                Files.writeString(base, root.toString(), StandardCharsets.UTF_8);
                Log.debug("Created base JSON {}", base);
            } catch (JsonParseException _) {
            }
        }

        return target;
    }

    public static JsonObject fetchVanillaJson(String mcVersion) throws IOException {
        String url = resolveUrl(mcVersion);
        if (url == null) throw new VersionNotFoundException(mcVersion);
        try {
            return JsonParser.parseString(Http.get(url)).getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("Malformed version JSON for " + mcVersion, e);
        }
    }

    private static String resolveUrl(String mcVersion) throws IOException {
        var versions = VersionManifest.getAll();
        if (versions == null) throw new IOException("Failed to fetch version manifest");
        for (var row : versions) {
            if (row != null && mcVersion.equals(row[VersionManifest.ID])) return row[VersionManifest.URL];
        }
        return null;
    }
}