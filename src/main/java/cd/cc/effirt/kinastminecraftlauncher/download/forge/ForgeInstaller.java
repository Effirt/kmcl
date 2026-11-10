package cd.cc.effirt.kinastminecraftlauncher.download.forge;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.InstallerRecords;
import cd.cc.effirt.kinastminecraftlauncher.download.ProcessorRecords;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.loader.Loaders;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.JavaFinder;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import cd.cc.effirt.kinastminecraftlauncher.util.XmlUtil;
import cd.cc.effirt.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Forge 1.13+ 安装流程。
 *
 * <p>Forge 的 version json 只写了 “我是 {@code inheritsFrom: <mcVersion>} 的补丁”，
 * 真正的客户端类由安装器里的 processors 加工出来（反混淆、打补丁）。因此这里不做
 * “解析 json 自己下载”的简化处理，而是：</p>
 *
 * <ol>
 *   <li>从 BMCLAPI（优先）或官方 Maven 下载 {@code forge-<mc>-<loader>-installer.jar}；</li>
 *   <li>改写 jar 内 json 的下载地址到镜像（BMCLAPI 的 Forge 安装器通常不改，
 *       源地址也可直接访问）；</li>
 *   <li>预下载 processors 需要的库，并补齐原版 client jar；</li>
 *   <li>执行 {@code java -jar <installer> --installClient <root>}；若无法执行 processors，
 *       降级为静态安装（把 {@code install_profile.json} 的库写进实例 json）。</li>
 * </ol>
 *
 * <p>流程细节照搬 <a href="https://github.com/Refitted-Team/ForgeWrapper">ForgeWrapper</a>
 * 的核心思路。</p>
 */
public class ForgeInstaller {

    private static final String OFFICIAL_MAVEN = "https://maven.minecraftforge.net/";
    private static final String PISTON_DATA = "https://piston-data.mojang.com/";
    private static final String MOJANG_LIBRARIES = "https://libraries.minecraft.net/";

    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String BMCLAPI_LIBRARIES = "https://bmclapi2.bangbang93.com/libraries/";
    private static final String BMCLAPI_ASSETS = "https://bmclapi2.bangbang93.com/assets/";
    private static final String FORGE_MAVEN = OFFICIAL_MAVEN + "net/minecraftforge/forge/";
    private static final String BMCLAPI_FORGE_MAVEN = BMCLAPI_MAVEN + "net/minecraftforge/forge/";
    private static final String FORGE_META = FORGE_MAVEN + "maven-metadata.xml";
    private static final String BMCLAPI_FORGE_META = BMCLAPI_FORGE_MAVEN + "maven-metadata.xml";
    private static final String FORGE_PROMOS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    // ==================== 入口 ====================

    /**
     * 安装 Forge 并登记实例。
     *
     * @param mcVersion    Minecraft 版本
     * @param requested    指定 loader 版本；null/空表示取推荐版
     * @param instanceName 实例名
     * @return 实例目录下的 {@code <mcVersion>.json}
     */
    public static Path install(String mcVersion, String requested, String instanceName)
            throws IOException {
        if (!VersionDownloader.exists(mcVersion)) {
            throw new cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException(mcVersion);
        }
        String loader;
        if (requested != null && !requested.isBlank()) {
            loader = requested.trim();
        } else {
            loader = resolveLatest(mcVersion);
            if (loader == null) {
                throw new IOException("No Forge build found for Minecraft " + mcVersion);
            }
        }

        Log.info("Forge Loader version: {}", loader);
        // 直接使用缓存里的原始安装器，不再生成改写过的 -mirror.jar 副本：
        // native-image 下对缓存目录里已有文件做覆盖写入会报 AccessDenied，
        // 而 installer 自身依赖的地址在可达网络上都能直连。
        Path runnable = downloadInstaller(mcVersion, loader);

        prepareProcessorsLibraries(runnable);
        ensureVanillaClient(mcVersion);

        boolean processed = runInstaller(runnable);

        JsonObject loaderJson;
        JsonObject installProfile = InstallerRecords.readJsonFromZip(runnable, "install_profile.json");
        // version.json 是安装器自带的 loader 版本 json，结构稳定
        JsonObject embedded = InstallerRecords.readJsonFromZip(runnable, "version.json");
        if (!processed) {
            Log.warn("Forge installer did not complete; installing statically from install_profile");
            loaderJson = staticVersionJson(installProfile, embedded, mcVersion, loader);
        } else if (embedded != null) {
            loaderJson = embedded;
        } else {
            Path installedDir = findInstalledDir(mcVersion, loader);
            if (installedDir == null) {
                throw new IOException("Forge installer produced no version json");
            }
            loaderJson = InstallerRecords.readJson(installJson(installedDir));
        }

        Path target = Loaders.writeInstanceJson(instanceName, mcVersion, loaderJson, Loaders.mojang());
        saveInstallProfile(instanceName, mcVersion, installProfile);
        Loaders.register(instanceName, mcVersion, "forge", loader);
        Log.info("Forge {} mapped to instance '{}' (processors {})",
                loader, instanceName, processed ? "executed" : "skipped");
        return target;
    }

    // ==================== 版本解析 ====================

    /** promotions_slim.json（推荐版）→ 同版本最新构建 → maven-metadata。 */
    static String resolveLatest(String mcVersion) {
        for (String suffix : new String[]{"-recommended", "-latest"}) {
            String v = promoVersion(mcVersion, suffix);
            if (v != null) return v;
        }
        List<String> all = listLoaders(mcVersion);
        if (all.isEmpty()) return null;
        all.sort((a, b) -> compareVersions(b, a));
        return all.getFirst();
    }

    private static String promoVersion(String mcVersion, String suffix) {
        try {
            Path cached = KMCLPath.getKmclPath().resolve("cache").resolve("forge-promotions.json");
            Files.createDirectories(cached.getParent());
            try (ProgressBar pb = new ProgressBar()) {
                pb.setCustomText("forge-promotions.json");
                pb.start();
                Http.getDownload(FORGE_PROMOS, cached, pb);
            }
            JsonObject root = InstallerRecords.readJson(cached);
            if (root.get("promos") instanceof JsonObject promos) {
                JsonElement v = promos.get(mcVersion + suffix);
                if (v != null && !v.isJsonNull() && !v.getAsString().isBlank()) {
                    return v.getAsString();
                }
            }
            return null;
        } catch (IOException e) {
            Log.warn("Forge promotions unreachable: {}", e.getMessage());
            return null;
        }
    }

    /** 列出某 MC 版本可用的 Forge loader 版本（由新到旧）。 */
    public static List<String> listLoaders(String mcVersion) {
        for (String meta : new String[]{BMCLAPI_FORGE_META, FORGE_META}) {
            try {
                List<String> out = new ArrayList<>();
                for (String tag : XmlUtil.readVersionTags(Http.get(meta))) {
                    String prefix = mcVersion + "-";
                    if (tag.startsWith(prefix)) {
                        String lv = tag.substring(prefix.length());
                        if (lv.matches("\\d[\\w.\\-]*")) out.add(lv);
                    }
                }
                if (!out.isEmpty()) {
                    Log.info("Getting {} Forge builds for {}", out.size(), mcVersion);
                    return out;
                }
            } catch (IOException e) {
                Log.warn("{} unreachable: {}", meta, e.getMessage());
            }
        }
        return List.of();
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntOr0(pa[i]) : 0;
            int vb = i < pb.length ? parseIntOr0(pb[i]) : 0;
            int c = Integer.compare(va, vb);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int parseIntOr0(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    // ==================== 安装器下载 ====================

    private static Path downloadInstaller(String mcVersion, String loader) throws IOException {
        String fileName = "forge-" + mcVersion + "-" + loader + "-installer.jar";
        Path target = KMCLPath.getKmclPath().resolve("cache").resolve(fileName);
        if (Files.isRegularFile(target) && Files.size(target) > 64 * 1024) {
            Log.info("Using cached Forge installer '{}'", fileName);
            return target;
        }

        String rel = mcVersion + "-" + loader + "/" + fileName;
        String bmcl = BMCLAPI_FORGE_MAVEN + rel;
        String official = FORGE_MAVEN + rel;
        Log.info("Downloading Forge installer...");
        IOException first = null;
        for (String url : new String[]{bmcl, official}) {
            try {
                try (ProgressBar pb = new ProgressBar()) {
                    pb.setCustomText(fileName);
                    pb.start();
                    Http.getDownload(url, target, pb);
                }
                if (Files.size(target) <= 64 * 1024) {
                    throw new IOException("Unexpectedly small installer: " + url);
                }
                return target;
            } catch (IOException e) {
                first = first == null ? e : first;
                Files.deleteIfExists(target);
                Log.warn("Failed: {} ({})", url, e.getMessage());
            }
        }
        throw new IOException("Failed to download Forge installer " + fileName, first);
    }

    // ==================== processors 前置库 ====================

    /**
     * 把所有 processor 引用的库预下载进 {@code libraries/}。
     * 与 ForgeWrapper 一致：{@code :addAll:} / {@code :extract:} 这类在运行时生成的目标
     * 只做存在性检查，不下载。
     */
    private static void prepareProcessorsLibraries(Path installer) throws IOException {
        JsonObject profile = InstallerRecords.readJsonFromZip(installer, "install_profile.json");
        List<ProcessorRecords.Processor> processors = ProcessorRecords.processors(profile);

        Set<String> wanted = new LinkedHashSet<>();
        for (ProcessorRecords.Processor p : processors) {
            if (p.jar() != null) wanted.add(p.jar());
            wanted.addAll(p.classpath());
        }
        if (wanted.isEmpty()) {
            Log.info("No processors listed in install_profile.json");
            return;
        }

        Path libsDir = KMCLPath.getKmclPath().resolve("libraries");
        String installerName = installer.getFileName().toString();
        int fetched = 0;
        for (String coord : wanted) {
            if (isGenerated(coord)) continue;
            Path dest = libsDir.resolve(mavenPath(coord));
            if (Files.isRegularFile(dest)) continue;
            Files.createDirectories(dest.getParent());
            if (dest.getFileName().toString().equals(installerName)) {
                Files.copy(installer, dest, StandardCopyOption.REPLACE_EXISTING);
                fetched++;
                continue;
            }
            try (ProgressBar pb = new ProgressBar()) {
                pb.setCustomText(dest.getFileName().toString());
                pb.start();
                Http.getDownload(BMCLAPI_MAVEN + mavenPath(coord), dest, pb);
            } catch (IOException e) {
                Files.deleteIfExists(dest);
                Log.warn("Failed to pre-download processor library {}: {}", coord, e.getMessage());
                continue;
            }
            fetched++;
        }
        Log.info("Pre-fetched {} processor libraries for {} processors", fetched, processors.size());
    }

    private static boolean isGenerated(String coord) {
        return coord.contains(":addAll:") || coord.contains(":extract:");
    }

    private static String mavenPath(String name) {
        String n = name;
        String ext = "jar";
        int at = n.indexOf('@');
        if (at >= 0) {
            ext = n.substring(at + 1);
            n = n.substring(0, at);
        }
        String[] parts = n.split(":");
        if (parts.length < 3) throw new IllegalArgumentException("Bad maven coordinate: " + name);
        StringBuilder cls = new StringBuilder();
        for (int i = 3; i < parts.length; i++) cls.append('-').append(parts[i]);
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2] + cls + "." + ext;
    }

    // ==================== 原版 client jar ====================

    /**
     * 确保 {@code versions/<mc>/<mc>.jar} 存在：ForgeWrapper 用 client jar 内的
     * forge 标记判断是否已安装，缺了会直接报 “Does not have Forge installed”。
     */
    private static void ensureVanillaClient(String mcVersion) throws IOException {
        Path dest = KMCLPath.getKmclPath().resolve("versions").resolve(mcVersion)
                .resolve(mcVersion + ".jar");
        if (Files.isRegularFile(dest) && Files.size(dest) > 1024) {
            return;
        }
        JsonObject vanilla = VersionDownloader.fetchVanillaJson(mcVersion);
        JsonObject downloads = vanilla.getAsJsonObject("downloads");
        JsonObject client = downloads == null ? null : downloads.getAsJsonObject("client");
        if (client == null || !client.has("url")) {
            Log.warn("No downloads.client for {}; Forge installer may refuse", mcVersion);
            return;
        }
        Files.createDirectories(dest.getParent());
        String url = client.get("url").getAsString()
                .replace(PISTON_DATA, BMCLAPI_ASSETS)
                .replace(MOJANG_LIBRARIES, BMCLAPI_LIBRARIES);
        Log.info("Downloading vanilla client jar for Forge...");
        try (ProgressBar pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".jar");
            pb.start();
            Http.getDownload(url, dest, pb);
        }
    }

    // ==================== 执行安装器 ====================

    private static boolean runInstaller(Path installer) throws IOException {
        Path root = KMCLPath.getKmclPath();
        Files.createDirectories(root);

        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Log.info("Creating launcher_profiles.json...");
            Files.writeString(profiles, """
                    {
                      "profiles": {},
                      "settings": {},
                      "version": 3
                    }
                    """, StandardCharsets.UTF_8);
        }

        Path java = JavaFinder.findAny();
        Log.info("Running Forge installer with '{}'...", java);

        List<String> cmd = new ArrayList<>();
        cmd.add(java.toAbsolutePath().toString());
        cmd.add("-Dhttp.agent=curl/8.21.0");
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && proxyPort != null) {
            cmd.add("-Dhttps.proxyHost=" + proxyHost);
            cmd.add("-Dhttps.proxyPort=" + proxyPort);
            String httpHost = System.getProperty("http.proxyHost");
            String httpPort = System.getProperty("http.proxyPort");
            if (httpHost != null && httpPort != null) {
                cmd.add("-Dhttp.proxyHost=" + httpHost);
                cmd.add("-Dhttp.proxyPort=" + httpPort);
            }
        }
        cmd.add("-jar");
        cmd.add(installer.toAbsolutePath().toString());
        cmd.add("--installClient");
        cmd.add(root.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(false);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process proc = pb.start();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                Log.warn("Forge installer exited with code {}", code);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Interrupted while running Forge installer", e);
        }
    }

    // ==================== 归位 ====================

    private static Path findInstalledDir(String mcVersion, String loader) throws IOException {
        String marker = "forge-" + mcVersion + "-" + loader;
        Path versions = KMCLPath.getKmclPath().resolve("versions");
        Path direct = versions.resolve(marker);
        if (Files.isDirectory(direct) && findJson(direct) != null) return direct;
        if (!Files.isDirectory(versions)) return null;
        try (var s = Files.list(versions)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("forge"))
                    .filter(p -> findJson(p) != null)
                    .findFirst().orElse(null);
        }
    }

    private static Path findJson(Path dir) {
        String name = dir.getFileName().toString();
        Path exact = dir.resolve(name + ".json");
        if (Files.isRegularFile(exact)) return exact;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path installJson(Path dir) throws IOException {
        Path json = findJson(dir);
        if (json == null) throw new IOException("No version json in " + dir);
        return json;
    }

    /** 静态安装：从 {@code install_profile.json} + {@code version.json} 组出实例 json。 */
    private static JsonObject staticVersionJson(JsonObject installProfile, JsonObject embedded,
                                                String mcVersion, String loader)
            throws IOException {
        if (embedded == null) {
            throw new IOException("Forge installer has no version.json; cannot install statically");
        }
        ProcessorRecords.InstallProfile profile = ProcessorRecords.profile(installProfile);
        if (profile.minecraft() != null && !profile.minecraft().equals(mcVersion)) {
            throw new IOException("Forge " + loader + " targets Minecraft "
                    + profile.minecraft() + ", not " + mcVersion);
        }
        JsonObject out = embedded.deepCopy();
        out.remove("librariesFrom");
        if (!out.has("id")) out.addProperty("id", "forge-" + mcVersion + "-" + loader);
        if (installProfile != null && installProfile.get("libraries") instanceof JsonObject libs) {
            JsonArray arr = out.get("libraries") instanceof JsonArray a ? a : new JsonArray();
            for (var e : libs.entrySet()) {
                if (e.getValue() instanceof JsonObject o) arr.add(o);
            }
            out.add("libraries", arr);
        }
        return out;
    }

    private static void saveInstallProfile(String instanceName, String mcVersion,
                                           JsonObject installProfile) {
        if (installProfile == null) return;
        try {
            Path dir = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(mcVersion + ".install.json"),
                    installProfile.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Failed to save install profile: {}", e.getMessage());
        }
    }
}