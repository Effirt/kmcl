package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.kinastminecraftlauncher.util.DownloadBatch;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

public class VersionInstaller {

    public static Path versionDir(String instanceName) {
        return KMCLPath.getKmclPath().resolve("versions").resolve(instanceName);
    }

    public static Path versionJson(String instanceName, String mcVersion) {
        return versionDir(instanceName).resolve(mcVersion + ".json");
    }

    public static Path clientJar(String instanceName, String mcVersion) {
        return versionDir(instanceName).resolve(mcVersion + ".jar");
    }

    public static Path nativesDir(String instanceName) {
        return versionDir(instanceName).resolve("natives");
    }

    public static Path assetsDir() {
        return KMCLPath.getKmclPath().resolve("assets");
    }

    // ============ client.jar ============

    public static void installClient(String instanceName, Path versionJson, int maxAttempts)
            throws IOException {
        installClient(instanceName, VersionJSONParser.parse(versionJson), maxAttempts);
    }

    public static void installClient(String instanceName, VersionJSONParser.VersionInfo info,
                                     int maxAttempts) throws IOException {
        var dl = info.downloads();
        if (dl == null || dl.client() == null || dl.client().url() == null)
            throw new IOException("version JSON has no downloads.client for id=" + info.id());

        String mcVersion = info.mcVersion();
        if (mcVersion == null || mcVersion.isBlank())
            throw new IOException("version JSON has no mcVersion, cannot resolve jar path");

        var art = dl.client();
        var dest = clientJar(instanceName, mcVersion);
        Files.createDirectories(dest.getParent());

        Log.info("Downloading {}.jar...", mcVersion);
        try (var pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".jar");
            pb.start();
            if (Files.exists(dest) && Files.size(dest) == art.size()) {
                pb.finish();
                return;
            }
            DownloadBatch.one(new DownloadBatch.Task(dl.client().url(), dl.client().size(), dest),
                    maxAttempts, pb);
        }
    }

    // ============ libraries ============

    public static void installLibraries(String instanceName, Path versionJson, int maxAttempts)
            throws IOException {
        installLibraries(instanceName, VersionJSONParser.parse(versionJson), maxAttempts);
    }

    public static void installLibraries(String instanceName, VersionJSONParser.VersionInfo info,
                                        int maxAttempts) throws IOException {
        var libsDir = KMCLPath.getKmclPath().resolve("libraries");
        var nativesDir = nativesDir(instanceName);
        Files.createDirectories(libsDir);

        if (Files.exists(nativesDir)) {
            try (var s = Files.walk(nativesDir)) {
                s.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(nativesDir))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException _) {
                            }
                        });
            }
        }
        Files.createDirectories(nativesDir);

        var tasks = new ArrayList<DownloadBatch.Task>();
        ArrayList<NativeEntryRecord> natives = new ArrayList<>();
        int skipped = 0;

        for (var lib : info.libraries()) {
            if (!lib.isAllowed()) continue;

            String url = null, path = null;
            long size = 0;
            VersionJSONParser.Artifact art;
            boolean isNativeLib = false;

            var nativeArt = lib.nativeArtifact();
            if (nativeArt != null && nativeArt.url() != null) {
                url = nativeArt.url();
                path = nativeArt.path();
                size = nativeArt.size();
                isNativeLib = true;
            } else if (lib.classifier() != null && lib.classifier().startsWith("natives-")
                    && lib.downloads() != null && lib.downloads().classifiers() != null) {
                var c = lib.downloads().classifiers().get(lib.classifier());
                if (c != null && c.url() != null) {
                    url = c.url();
                    path = c.path();
                    size = c.size();
                    isNativeLib = true;
                }
            } else if (lib.downloads() != null && lib.downloads().artifact() != null
                    && lib.downloads().artifact().url() != null) {
                art = lib.downloads().artifact();
                url = art.url();
                path = (art.path() != null && !art.path().isBlank()) ? art.path() : lib.mavenPath();
                size = art.size();
                isNativeLib = art.path() != null && art.path().contains("-natives-");
            } else if (lib.url() != null && !lib.url().isBlank()
                    && lib.groupId() != null && lib.artifactId() != null && lib.version() != null) {
                String base = lib.url().endsWith("/") ? lib.url() : lib.url() + "/";
                path = lib.mavenPath();
                url = base + path;
            } else {
                Log.warn("Skip library without resolvable URL: {}", lib.name());
                skipped++;
                continue;
            }

            Path dest = null;
            if (path != null) {
                dest = libsDir.resolve(path);
            }
            tasks.add(new DownloadBatch.Task(url, size, dest));
            if (isNativeLib) natives.add(new NativeEntryRecord(dest, lib.extract()));
        }

        Log.info("Installing {} libraries, {} natives, {} skipped...",
                tasks.size(), natives.size(), skipped);
        DownloadBatch.run(tasks, maxAttempts, "libraries");

        Log.info("Extracting {} native jar...", natives.size());
        for (NativeEntryRecord ne : natives) {
            if (!Files.exists(ne.jar())) continue;
            try {
                NativeExtractor.extract(ne.jar(), nativesDir, ne.extract());
            } catch (IOException e) {
                Log.warn("Extract native '{}' failed: {}", ne.jar(), e.getMessage());
            }
        }
    }

    // ============ assets ============

    public static void installAssets(Path versionJson, int maxAttempts) throws IOException {
        AssetInstaller.install(VersionJSONParser.parse(versionJson), maxAttempts);
    }

    public static Path instanceGameDir(String name) {
        return KMCLPath.getKmclPath().resolve("instances").resolve(name);
    }
}