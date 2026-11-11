package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.auroralib.Log;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class VersionDownloader {

    public static Path downloadVanillaJson(String mcVersion, String instanceName)
            throws IOException {
        String url = resolveUrl(mcVersion);
        if (url == null) throw new VersionNotFoundException(mcVersion);

        Path target = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve(mcVersion + ".json");
        Files.createDirectories(target.getParent());

        Log.info("Downloading {} version JSON...", mcVersion);
        try (var pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".json");
            pb.start();
            Http.getDownload(url, target, pb);
        }

        // 只校验能被解析，不做任何字段注入
        try {
            JsonParser.parseString(Files.readString(target, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("Malformed version JSON from " + url, e);
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

    /** 原版版本是否存在（在 manifest 里）。 */
    public static boolean exists(String mcVersion) throws IOException {
        return resolveUrl(mcVersion) != null;
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