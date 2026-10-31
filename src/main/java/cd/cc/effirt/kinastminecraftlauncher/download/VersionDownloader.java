package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.util.Log;
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

    public static Path downloadVanillaJson(String mcVersion, String name) throws IOException {
        String url = resolveUrl(mcVersion);
        if (url == null) throw new VersionNotFoundException(mcVersion);

        Path target = VersionInstaller.versionDir(name).resolve(name + ".json");
        Files.createDirectories(target.getParent());

        Log.info("Downloading {} version JSON...", mcVersion);
        try (var pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".json");
            pb.start();
            Http.getDownload(url, target, pb);
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