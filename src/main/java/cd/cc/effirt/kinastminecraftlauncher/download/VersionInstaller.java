package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.kinastminecraftlauncher.util.DownloadBatch;
import cd.cc.effirt.kinastminecraftlauncher.util.Mirrors;
import cd.cc.effirt.auroralib.Log;
import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

public class VersionInstaller {

    /**
     * Maven 坐标：{@code group:artifact:version[:classifier][@ext]}。
     * Forge 1.17+ 的 version json 用这种写法引用本来就随安装器下载好的 loader 库，
     * 这些库<b>不</b>带 {@code downloads}，也不应再按名字去网上找。
     */
    private static final Pattern MAVEN_COORDINATE =
            Pattern.compile("[\\w.\\-]+:[\\w.\\-]+:[\\w.\\-]+(:[\\w.\\-]+)?(@\\w+)?");

    private static boolean hasMavenCoordinateOnly(VersionJSONParser.Library lib) {
        if (lib.downloads() != null) return false;
        String name = lib.name();
        return name != null && MAVEN_COORDINATE.matcher(name).matches();
    }

    // ============ client.jar ============

    public static void installClient(String instanceName, Path versionJson, int maxAttempts)
            throws IOException {
        installClient(instanceName, VersionJSONParser.parse(versionJson), maxAttempts);
    }

    public static void installClient(String instanceName, VersionJSONParser.VersionInfo info,
                                     int maxAttempts) throws IOException {
        String mcVersion = info.mcVersion();
        installClient(instanceName, info, maxAttempts,
                (mcVersion == null || mcVersion.isBlank()) ? instanceName + ".jar" : mcVersion + ".jar");
    }

    /**
     * 下载 {@code downloads.client}。
     *
     * @param jarName 实例目录下的目标文件名，默认 {@code <mcVersion>.jar}
     */
    public static void installClient(String instanceName, VersionJSONParser.VersionInfo info,
                                     int maxAttempts, String jarName) throws IOException {
        var dl = info.downloads();
        if (dl == null || dl.client() == null || dl.client().url() == null)
            throw new IOException("version JSON has no downloads.client for id=" + info.id());

        String mcVersion = info.mcVersion();
        if (mcVersion == null || mcVersion.isBlank())
            throw new IOException("version JSON has no mcVersion, cannot resolve jar path");

        var art = dl.client();
        var dest = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve(jarName);
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest) && art.size() > 0 && Files.size(dest) == art.size()) {
            return;
        }

        Log.info("Downloading {}.jar...", mcVersion);
        try (var pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".jar");
            pb.start();
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
        var nativesDir = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve("natives");
        Files.createDirectories(libsDir);

        if (Files.exists(nativesDir)) {
            try (var s = Files.walk(nativesDir)) {
                s.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(nativesDir))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException _) {}
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
            } else if (hasMavenCoordinateOnly(lib)) {
                // 已由安装器（Forge/NeoForge processors）放进 libraries/
                path = lib.mavenPath();
                url = null;
            } else {
                Log.warn("Skip library without resolvable URL: {}", lib.name());
                skipped++;
                continue;
            }

            Path dest = path == null ? null : libsDir.resolve(path);
            if (url == null) {
                if (dest == null || !Files.exists(dest)) {
                    Log.warn("Library not installed by loader: {}", lib.name());
                    skipped++;
                }
                continue;
            }
            tasks.add(new DownloadBatch.Task(url, size, dest));
            if (isNativeLib) natives.add(new NativeEntryRecord(dest, lib.extract()));
        }

        long mirrorable = tasks.stream()
                .filter(t -> Mirrors.mirrorable(t.url())).count();
        Log.info("Installing {} libraries, {} natives, {} skipped, {} mirror-fallback...",
                tasks.size(), natives.size(), skipped, mirrorable);
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