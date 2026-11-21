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

    /** 老版本 Forge 缺失库的公开副本所在仓库（官方优先，失败自动回落镜像）。 */
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    /** Mojang 的库仓库：launchwrapper、vecmath、lzma 等只在这里，不在 Maven Central。 */
    private static final String LIBRARIES_MINECRAFT = "https://libraries.minecraft.net/";

    private static boolean hasMavenCoordinateOnly(VersionJSONParser.Library lib) {
        if (lib.downloads() != null) return false;
        String name = lib.name();
        return name != null && MAVEN_COORDINATE.matcher(name).matches();
    }

    /**
     * 只有坐标的库的补充下载地址。
     *
     * <p>老版本 Forge（1.8.9 / 1.12.2 等）的 version json 里，部分库只写了坐标而没有
     * {@code downloads}：{@code net.minecraft:launchwrapper}、{@code org.ow2.asm:asm-all}、
     * {@code lzma:lzma}、{@code net.sf.jopt-simple}、{@code java3d:vecmath}、
     * {@code net.sf.trove4j} 等。它们不在安装器里，缺失会直接导致
     * {@code 找不到或无法加载主类 net.minecraft.launchwrapper.Launch}。</p>
     *
     * <ul>
     *   <li>Mojang 自己的库（{@code net.minecraft:*}) 在 {@code libraries.minecraft.net}，
     *       该仓库<b>不在</b> Maven Central，不能猜成 repo1；</li>
     *   <li>其余开源库在 Maven Central；</li>
     *   <li>{@code tv.twitch:*} 只在 Mojang CDN 且未公开，保持跳过。</li>
     * </ul>
     *
     * @return 下载地址；无法确定时返回 {@code null}（保持“跳过”语义）
     */
    private static String coordinateOnlyUrl(VersionJSONParser.Library lib) {
        String path = lib.mavenPath();
        String group = lib.groupId();
        if (path.isBlank() || group == null) return null;
        return switch (group) {
            case "tv.twitch" -> null;
            case "net.minecraft" -> LIBRARIES_MINECRAFT + path;
            // 已知只在 Mojang 库里的老库
            case "java3d", "lzma", "net.sf.jopt-simple", "net.sf.trove4j" -> LIBRARIES_MINECRAFT + path;
            default -> MAVEN_CENTRAL + path;
        };
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
    public static void installClient(String instanceName, VersionJSONParser.VersionInfo info, int maxAttempts, String jarName) throws IOException {
        var dl = info.downloads();
        if (dl == null || dl.client() == null || dl.client().url() == null)
            throw new IOException("version JSON has no downloads.client for id=" + info.id());

        String mcVersion = info.mcVersion();
        if (mcVersion == null || mcVersion.isBlank())
            throw new IOException("version JSON has no mcVersion, cannot resolve jar path");

        var art = dl.client();
        var dest = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve(jarName);
        Files.createDirectories(dest.getParent());

        Log.info("Downloading {}.jar...", mcVersion);
        try (var pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".jar");
            pb.start();
            if (Files.exists(dest) && art.size() > 0 && Files.size(dest) == art.size()) {
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
                // 装不进去的 loader 库（老版本 Forge 的 launchwrapper / asm-all / lzma /
                // jopt-simple / vecmath / trove4j）在 Maven Central 上有公开副本，补一个回退地址
                url = coordinateOnlyUrl(lib);
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
        Log.info("Installing {} libraries, {} natives, {} skip, {} mirror-fallback...",
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
}