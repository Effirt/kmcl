package cd.cc.effirt.kinastminecraftlauncher.download.forge;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.InstallerRecords;
import cd.cc.effirt.kinastminecraftlauncher.download.ProcessorRecords;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.loader.Loaders;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.JavaFinder;
import cd.cc.effirt.kinastminecraftlauncher.util.Mirrors;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import cd.cc.effirt.kinastminecraftlauncher.util.XmlUtil;
import cd.cc.effirt.auroralib.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final String FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

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

        JsonObject installProfile = InstallerRecords.readJsonFromZip(runnable, "install_profile.json");
        JsonObject embedded = InstallerRecords.readInstallerVersionJson(runnable, installProfile);

        // ===== 老式安装器（1.12 及更早）：没有 processors，也不需要跑安装器 =====
        //
        // 这类安装器是 SimpleInstaller，参数是 --installClient；但它只负责把 universal
        // 写进版本目录，version json 就在 install_profile.json 的 install 字段里。
        // 直接静态安装即可（equivalent 于官方安装器的产物）。
        boolean modern = embedded != null
                && InstallerRecords.readJsonFromZip(runnable, "version.json") != null;
        if (!modern) {
            Log.info("Legacy Forge installer (no processors), installing statically...");
            JsonObject legacyJson = staticVersionJson(installProfile, embedded, mcVersion, loader);
            // SimpleInstaller（1.8.9 等）把 universal jar 打包在安装器内部，
            // 它的版本 json 只声明了 net.minecraftforge:forge 库而没有可访问的 URL，
            // 这里把它释放到 libraries/ 下，避免启动时 404。
            extractLegacyUniversal(runnable, installProfile, mcVersion, loader);
            Path target = Loaders.writeInstanceJson(instanceName, mcVersion, legacyJson, Loaders.mojang());
            saveInstallProfile(instanceName, mcVersion, installProfile);
            Loaders.register(instanceName, mcVersion, "forge", loader);
            Log.info("Forge {} mapped to instance '{}'", loader, instanceName);
            return target;
        }

        prepareProcessorsLibraries(runnable);
        ensureVanillaClient(mcVersion);

        boolean processed = runInstaller(runnable);

        JsonObject loaderJson;
        if (!processed) {
            Log.warn("Forge installer did not complete; installing statically from install_profile");
            loaderJson = staticVersionJson(installProfile, embedded, mcVersion, loader);
        } else {
            loaderJson = embedded;
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
        for (String meta : new String[]{FORGE_META, BMCLAPI_FORGE_META}) {
            try {
                List<String> out = new ArrayList<>();
                Log.info("Getting Forge loader list...");
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

    /**
     * Forge Maven 里的版本坐标。
     *
     * <p>1.8.9 / 1.9.x / 1.10.x / 1.11.x / 1.12.x 这些老版本的 Forge 目录名不是
     * {@code <mc>-<loader>}，而是带 MC 后缀的 {@code <mc>-<loader>-<mc>}
     * （例如 {@code 1.8.9-11.15.1.2318-1.8.9}）。用错坐标会得到 404。</p>
     */
    private static String versionPath(String mcVersion, String loader) {
        return needsMcSuffix(mcVersion)
                ? mcVersion + "-" + loader + "-" + mcVersion
                : mcVersion + "-" + loader;
    }

    /**
     * 该 MC 版本的 Forge 目录是否使用 {@code <mc>-<loader>-<mc>} 形式。
     *
     * <p>1.13+ 全部是 {@code <mc>-<loader>}；1.8 及更早的裸版本也是；
     * 1.8.9 / 1.9.x / 1.10.x / 1.11.x / 1.12.x 带 MC 后缀。</p>
     */
    static boolean needsMcSuffix(String mcVersion) {
        if (mcVersion == null || !mcVersion.startsWith("1.")) return false;
        try {
            String[] p = mcVersion.substring(2).split("\\.");
            int minor = Integer.parseInt(p[0].replaceAll("\\D", "0"));
            int patch = p.length > 1 ? Integer.parseInt(p[1].replaceAll("\\D", "0")) : 0;
            if (minor < 8) return false;
            if (minor == 8) return patch == 9;              // 1.8.9 带后缀，1.8 不带
            return minor <= 12;                             // 1.9.x ~ 1.12.x
        } catch (NumberFormatException _) {
            return false;
        }
    }

    private static Path downloadInstaller(String mcVersion, String loader) throws IOException {
        String vpath = versionPath(mcVersion, loader);
        String fileName = "forge-" + vpath + "-installer.jar";
        Path target = KMCLPath.getKmclPath().resolve("cache").resolve(fileName);
        if (Files.isRegularFile(target) && Files.size(target) > 64 * 1024) {
            Log.info("Using cached Forge installer '{}'", fileName);
            return target;
        }

        String rel = vpath + "/" + fileName;
        String bmcl = BMCLAPI_FORGE_MAVEN + rel;
        String official = FORGE_MAVEN + rel;
        Log.info("Downloading Forge installer...");
        IOException first = null;
        for (String url : new String[]{official, bmcl}) {
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
        Log.info("Downloading libraries...");
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
                // 官方优先，失败后换 BMCLAPI 镜像
                List<String> sources = Mirrors.candidates(OFFICIAL_MAVEN + mavenPath(coord));
                IOException last = null;
                for (String url : sources) {
                    try {
                        Http.getDownload(url, dest, pb);
                        last = null;
                        break;
                    } catch (IOException e) {
                        last = e;
                        Files.deleteIfExists(dest);
                    }
                }
                if (last != null) throw last;
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
        // client.jar 走 Mojang 的 piston-data 地址，镜像并不完整（BMCLAPI 对
        // piston-data 的 v1/objects 路径会返回 404），因此这里<b>先官方、失败再试镜像</b>，
        // 而不是一律改写成镜像地址。
        String official = client.get("url").getAsString();
        List<String> candidates = new ArrayList<>(Mirrors.candidates(official));
        if (candidates.size() == 1) {
            // 官方地址不在 Mirrors 的映射表里（piston-data），手动追加一个镜像候选
            String mirrored = official
                    .replace(PISTON_DATA, BMCLAPI_ASSETS)
                    .replace(MOJANG_LIBRARIES, BMCLAPI_LIBRARIES);
            if (!mirrored.equals(official)) candidates.add(mirrored);
        }

        Log.info("Downloading vanilla client jar for Forge...");
        try (ProgressBar pb = new ProgressBar()) {
            pb.setCustomText(mcVersion + ".jar");
            pb.start();
            IOException last = null;
            for (String url : candidates) {
                try {
                    Http.getDownload(url, dest, pb);
                    if (Files.isRegularFile(dest) && Files.size(dest) > 1024) return;
                    last = new IOException("Unexpectedly small client jar: " + url);
                    Files.deleteIfExists(dest);
                } catch (IOException e) {
                    last = e;
                    Files.deleteIfExists(dest);
                    Log.warn("Failed: {} ({})", url, e.getMessage());
                }
            }
            throw new IOException("Failed to download vanilla client jar for " + mcVersion, last);
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
        try (ProgressBar prb = new ProgressBar()) {
            prb.setSpinner(true);
            prb.setProgressBar(false);
            prb.setCustomText("Running Forge installer...");
            prb.start();

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
    }

    // ==================== 归位 ====================

    /**
     * 静态安装：从安装器内嵌的版本 json（{@code version.json} 或老式
     * {@code install_profile.install}）组出实例 json，并把 {@code install_profile.libraries} 合并进去。
     *
     * <p>老式（1.12 及更早）安装器的版本 json 没有 {@code jar} 字段，客户端 jar 就是
     * {@code <mc>.jar}；{@code install_profile.install.filePath} 指向的 universal jar
     * 也会作为库并入（地址为 {@code maven.minecraftforge.net}，走镜像回退）。</p>
     */
    private static JsonObject staticVersionJson(JsonObject installProfile, JsonObject embedded,
                                                String mcVersion, String loader)
            throws IOException {
        if (embedded == null) {
            throw new IOException("Forge installer has no version json (neither version.json "
                    + "nor install_profile.install); cannot install statically");
        }
        ProcessorRecords.InstallProfile profile = ProcessorRecords.profile(installProfile);
        if (profile.minecraft() != null && !profile.minecraft().equals(mcVersion)) {
            throw new IOException("Forge " + loader + " targets Minecraft "
                    + profile.minecraft() + ", not " + mcVersion);
        }
        JsonObject out = embedded.deepCopy();
        out.remove("librariesFrom");
        if (!out.has("id")) out.addProperty("id", "forge-" + mcVersion + "-" + loader);

        JsonArray arr = out.get("libraries") instanceof JsonArray a ? a : new JsonArray();

        // 老式 install_profile 内嵌的 libraries（processor 相关，通常为空）
        if (installProfile != null && installProfile.get("libraries") instanceof JsonObject libs) {
            for (var e : libs.entrySet()) {
                if (e.getValue() instanceof JsonObject o) arr.add(o);
            }
        }

        // 老式安装器（SimpleInstaller）：安装器内部带着 universal jar，
        // extractLegacyUniversal 已把它释放到 libraries/，这里把版本 json 里的
        // forge 库坐标对齐到该文件（并用带 -<mc> 后缀的 Maven 坐标）。
        if (installProfile != null && installProfile.get("install") instanceof JsonObject legacyInstall
                && str(legacyInstall, "filePath") != null) {
            normalizeLegacyForgeLibrary(arr, mcVersion, loader);
            demoteClientJar(arr);

            // 老式 Forge 的 universal jar 是 universal（混淆名），需要 launchwrapper 在
            // 运行时把 net.minecraft.client.Minecraft 之类的类名重映射到混淆名。
            // 版本 json 的 minecraftArguments 里本来就带了 FMLTweaker，但 mc 版本的
            // arguments 在合并时可能被丢掉，这里兜底补上 tweak 参数（只补一次）。
            String tweakClass = "net.minecraftforge.fml.common.launcher.FMLTweaker";
            String mcArgs = str(out, "minecraftArguments");
            if (mcArgs == null || mcArgs.isBlank()) {
                mcArgs = DEFAULT_LEGACY_ARGUMENTS;
            }
            if (!mcArgs.contains("--tweakClass")) {
                mcArgs = mcArgs + " --tweakClass " + tweakClass;
            }
            out.addProperty("minecraftArguments", mcArgs);
        }

        out.add("libraries", arr);
        return out;
    }

    /**
     * 老式版本 json 若没有 {@code minecraftArguments}，用这套默认参数兜底
     * （与官方安装器写入 {@code install_profile.versionInfo} 的内容一致）。
     */
    private static final String DEFAULT_LEGACY_ARGUMENTS =
            "--username ${auth_player_name} --version ${version_name} "
                    + "--gameDir ${game_directory} --assetsDir ${assets_root} "
                    + "--assetIndex ${assets_index_name} --uuid ${auth_uuid} "
                    + "--accessToken ${auth_access_token} --userProperties ${user_properties} "
                    + "--userType ${user_type}";

    /**
     * 老式版本 json 自带 {@code downloads.client}，而 {@code Loaders.writeInstanceJson}
     * 会把原版 json 的 {@code downloads}/{@code libraries} 按组合并进来。
     * 合并会把原版 client.jar 对应的 {@code net.minecraft:client} 库推到最后，
     * 那样 launchwrapper 会先加载原版混淆类而不是 universal 里的 FML 类。
     * 这里把原版 client 库移到最前面（classpath 顺序：forge 库最先）。
     */
    private static void demoteClientJar(JsonArray arr) {
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!(el instanceof JsonObject o)) continue;
            String name = str(o, "name");
            if (name != null && name.startsWith("net.minecraft:client:")) {
                if (i == 0) return;
                // gson 的 JsonArray 没有 add(index, element)
                JsonArray reordered = new JsonArray();
                reordered.add(o);
                for (int k = 0; k < arr.size(); k++) {
                    if (k != i) reordered.add(arr.get(k));
                }
                // 就地替换，保持调用方持有的引用有效
                while (!arr.isEmpty()) arr.remove(0);
                for (JsonElement e : reordered) arr.add(e);
                return;
            }
        }
    }

    /** 把版本 json 里的 {@code net.minecraftforge:forge} 库指向释放出来的 universal jar。 */
    private static void normalizeLegacyForgeLibrary(JsonArray arr, String mcVersion, String loader) {
        String v = versionPath(mcVersion, loader);
        String rel = "net/minecraftforge/forge/" + v + "/forge-" + v + "-universal.jar";
        boolean found = false;
        for (JsonElement el : arr) {
            if (!(el instanceof JsonObject o)) continue;
            String name = str(o, "name");
            if (name == null || !name.startsWith("net.minecraftforge:forge:")) continue;
            found = true;
            o.addProperty("name", "net.minecraftforge:forge:" + v + ":universal");
            JsonObject artifact = new JsonObject();
            artifact.addProperty("path", rel);
            artifact.addProperty("url", OFFICIAL_MAVEN + rel);
            JsonObject downloads = new JsonObject();
            downloads.add("artifact", artifact);
            o.add("downloads", downloads);
        }
        if (!found) {
            JsonObject lib = new JsonObject();
            lib.addProperty("name", "net.minecraftforge:forge:" + v + ":universal");
            JsonObject artifact = new JsonObject();
            artifact.addProperty("path", rel);
            artifact.addProperty("url", OFFICIAL_MAVEN + rel);
            JsonObject downloads = new JsonObject();
            downloads.add("artifact", artifact);
            lib.add("downloads", downloads);
            arr.add(lib);
        }
    }

    /**
     * SimpleInstaller（1.8.9 / 1.9.x / 1.10.x）把 universal jar 打包在安装器 jar 内部
     * （条目名就是 {@code install.filePath}，例如
     * {@code forge-1.8.9-11.15.1.2318-1.8.9-universal.jar}），
     * 而它的版本 json 只声明了 {@code net.minecraftforge:forge:...} 库、没有下载 URL。
     * 这里把该条目释放到 {@code libraries/net/minecraftforge/forge/<v>/} 下，
     * 并同步版本 json 中的库坐标，保证启动时 classpath 能找到它。
     */
    private static void extractLegacyUniversal(Path installer, JsonObject installProfile,
                                               String mcVersion, String loader) throws IOException {
        if (installProfile == null
                || !(installProfile.get("install") instanceof JsonObject installInstall)) {
            return;
        }
        String filePath = str(installInstall, "filePath");
        if (filePath == null || filePath.isBlank()) return;

        String v = versionPath(mcVersion, loader);
        String rel = "net/minecraftforge/forge/" + v + "/forge-" + v + "-universal.jar";
        Path dest = KMCLPath.getKmclPath().resolve("libraries").resolve(rel);
        if (Files.isRegularFile(dest) && Files.size(dest) > 1024) {
            return;
        }

        // filePath 可能是裸文件名，也可能带目录；两种都尝试
        String entryName = filePath.replace('\\', '/');
        if (entryName.startsWith("/")) entryName = entryName.substring(1);
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                // 退一步：按文件名在 jar 里找
                String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                var en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry candidate = en.nextElement();
                    String n = candidate.getName();
                    if (!candidate.isDirectory()
                            && n.substring(n.lastIndexOf('/') + 1).equals(fileName)) {
                        entry = candidate;
                        break;
                    }
                }
            }
            if (entry == null) {
                Log.warn("Universal jar '{}' not found inside Forge installer", filePath);
                return;
            }
            Files.createDirectories(dest.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Log.info("Extracted Forge universal jar -> {}", rel);
    }

    /** 读取 JSON 字符串字段；缺失或 null 时返回 {@code null}。 */
    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
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