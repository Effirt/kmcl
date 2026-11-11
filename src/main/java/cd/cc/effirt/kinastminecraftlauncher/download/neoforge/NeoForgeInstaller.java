package cd.cc.effirt.kinastminecraftlauncher.download.neoforge;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.InstallerRecords;
import cd.cc.effirt.kinastminecraftlauncher.download.ProcessorRecords;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.loader.Loaders;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.JavaFinder;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import cd.cc.effirt.kinastminecraftlauncher.util.XmlUtil;
import cd.cc.effirt.auroralib.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * NeoForge 安装流程（与 Forge 同属 “安装器 + processors” 体系）。
 *
 * <p>与 Forge 的差异：</p>
 * <ul>
 *   <li>版本号形如 {@code 20.4.237} / {@code 21.1.0}，前两段对应 Minecraft 版本
 *       （{@code 20.4} → 1.20.4，{@code 21.1} → 1.21.1），由此筛选；</li>
 *   <li>仓库 {@code maven.neoforged.net} 在部分网络下不可达，因此优先使用
 *       BMCLAPI 的 Maven 镜像；</li>
 *   <li>安装器同样执行 {@code --installClient}，失败时降级为静态安装。</li>
 * </ul>
 */
public class NeoForgeInstaller {

    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String BMCLAPI_META = BMCLAPI_MAVEN + "net/neoforged/neoforge/maven-metadata.xml";
    private static final String OFFICIAL_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String OFFICIAL_META = OFFICIAL_MAVEN + "net/neoforged/neoforge/maven-metadata.xml";

    // ==================== 入口 ====================

    public static Path install(String mcVersion, String requested, String instanceName)
            throws IOException {
        if (!VersionDownloader.exists(mcVersion)) {
            throw new VersionNotFoundException(mcVersion);
        }
        String loader = (requested == null || requested.isBlank())
                ? resolveLatest(mcVersion)
                : requested.trim();
        if (loader == null) {
            throw new IOException("No NeoForge versions found for Minecraft " + mcVersion);
        }
        Log.info("NeoForge Loader version: {}", loader);

        // 先官方安装器，失败后 BMCLAPI 镜像；执行前把 json 内的仓库地址改写成镜像
        Path runnable = patchInstallerForMirror(downloadInstaller(loader));
        boolean processed = runInstaller(runnable);

        JsonObject installProfile = InstallerRecords.readJsonFromZip(runnable, "install_profile.json");
        JsonObject embedded = InstallerRecords.readJsonFromZip(runnable, "version.json");
        JsonObject loaderJson;
        if (processed && embedded != null) {
            loaderJson = embedded;
        } else {
            if (processed) {
                Log.warn("NeoForge installer produced no version.json; using install_profile");
            } else {
                Log.warn("NeoForge installer did not complete; installing statically from install_profile");
            }
            loaderJson = staticVersionJson(installProfile, embedded, loader);
        }

        Path target = Loaders.writeInstanceJson(instanceName, mcVersion, loaderJson, Loaders.mojang());
        saveInstallProfile(instanceName, mcVersion, installProfile);
        Loaders.register(instanceName, mcVersion, "neoforge", loader);
        Log.info("NeoForge {} mapped to instance '{}' (processors {})",
                loader, instanceName, processed ? "executed" : "skipped");
        return target;
    }

    // ==================== 版本解析 ====================

    /** 取同 MC 版本下最新的 NeoForge 构建：优先正式版，没有正式版才退回 beta。 */
    static String resolveLatest(String mcVersion) {
        List<String> matched = new ArrayList<>();
        for (String v : fetchVersions()) {
            if (matches(v, mcVersion)) matched.add(v);
        }
        if (matched.isEmpty()) return null;
        matched.sort(NeoForgeInstaller::compareVersions);

        // 正式版优先（21.8.0-beta 这种预发布只在没有正式版时使用）
        for (int i = matched.size() - 1; i >= 0; i--) {
            if (!isPrerelease(matched.get(i))) return matched.get(i);
        }
        String beta = matched.getLast();
        Log.warn("Only pre-release NeoForge builds found for {} (using {})", mcVersion, beta);
        return beta;
    }

    /** beta / alpha / pre / rc 等预发布后缀。 */
    static boolean isPrerelease(String version) {
        String v = version.toLowerCase(java.util.Locale.ROOT);
        return v.contains("-beta") || v.contains("-alpha")
                || v.contains("-pre") || v.contains("-rc");
    }

    /** 列出某 MC 版本可用的 NeoForge 版本（由新到旧）。 */
    public static List<String> listLoaders(String mcVersion) {
        List<String> matched = new ArrayList<>();
        for (String v : fetchVersions()) {
            if (matches(v, mcVersion)) matched.add(v);
        }
        matched.sort((a, b) -> compareVersions(b, a));
        return matched;
    }

    /**
     * NeoForge 版本号与 MC 版本的对应关系：
     * {@code 1.20.4} → {@code 20.4.x}；{@code 1.21} → {@code 21.0.x}；{@code 1.20.1} 不存在。
     */
    static boolean matches(String neoVersion, String mcVersion) {
        List<String> parts = numericPrefix(neoVersion);
        if (parts.size() < 2) return false;
        String expected = mcMinor(mcVersion);
        return (parts.get(0) + "." + parts.get(1)).equals(expected);
    }

    private static String mcMinor(String mcVersion) {
        if (!mcVersion.startsWith("1.")) return null;
        String[] p = mcVersion.substring(2).split("\\.");
        if (p.length == 0 || p[0].isBlank()) return null;
        String minor = Integer.toString(Integer.parseInt(p[0].replaceAll("\\D", "0")));
        String patch = p.length > 1 ? p[1].replaceAll("\\D", "") : "0";
        return minor + "." + (patch.isEmpty() ? "0" : patch);
    }

    private static List<String> numericPrefix(String v) {
        List<String> out = new ArrayList<>();
        String core = v.contains("-") ? v.substring(0, v.indexOf('-')) : v;
        for (String s : core.split("\\.")) {
            if (!s.matches("\\d+")) break;
            out.add(s);
        }
        return out;
    }

    private static List<String> fetchVersions() {
        // 先官方，再 BMCLAPI 镜像
        for (String url : new String[]{OFFICIAL_META, BMCLAPI_META}) {
            try {
                List<String> all = XmlUtil.readVersionTags(Http.get(url));
                if (!all.isEmpty()) {
                    Log.info("Getting {} NeoForge versions...", all.size());
                    return all;
                }
            } catch (IOException e) {
                Log.warn("{} unreachable: {}", url, e.getMessage());
            }
        }
        return List.of();
    }

    private static int compareVersions(String a, String b) {
        List<String> pa = numericPrefix(b);
        List<String> pb = numericPrefix(a);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            int va = i < pa.size() ? Integer.parseInt(pa.get(i)) : 0;
            int vb = i < pb.size() ? Integer.parseInt(pb.get(i)) : 0;
            int c = Integer.compare(va, vb);
            if (c != 0) return c;
        }
        // 数字相同比预发布：正式版排在 beta 之后（升序时正式版更"大"）
        boolean preA = isPrerelease(a), preB = isPrerelease(b);
        if (preA != preB) return preA ? 1 : -1;
        return 0;
    }

    // ==================== 安装器下载 ====================

    private static Path downloadInstaller(String loader) throws IOException {
        String fileName = "neoforge-" + loader + "-installer.jar";
        Path target = KMCLPath.getKmclPath().resolve("cache").resolve(fileName);
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target) && Files.size(target) > 64 * 1024) {
            Log.info("Using cached NeoForge installer '{}'", fileName);
            return target;
        }

        String bmclUrl = BMCLAPI_MAVEN + "net/neoforged/neoforge/" + loader + "/" + fileName;
        String onUrl = OFFICIAL_MAVEN + "net/neoforged/neoforge/" + loader + "/" + fileName;
        Log.info("Downloading NeoForge installer...");
        IOException first = null;
        // 先官方，失败后 BMCLAPI 镜像
        for (String url : new String[]{onUrl, bmclUrl}) {
            try {
                try (ProgressBar pb = new ProgressBar()) {
                    pb.setCustomText(fileName);
                    pb.start();
                    Http.getDownload(url, target, pb);
                }
                return target;
            } catch (IOException e) {
                first = first == null ? e : first;
                Files.deleteIfExists(target);
                Log.warn("Failed: {} ({})", url, e.getMessage());
            }
        }
        throw new IOException("Failed to download NeoForge installer " + fileName, first);
    }


    // ==================== 执行安装器 ====================

    /**
     * 把安装器里 install_profile.json / version.json 的官方仓库地址改写成 BMCLAPI 镜像，
     * 生成一个可执行的副本。
     *
     * <p>安装器进程会按 json 里的地址下载全部依赖；官方 maven.neoforged.net 在部分网络下
     * 不可达时，不改写就必然 code 1 退出、processors 跑不完（实例启动时报
     * {@code NoSuchElementException: No value present}）。</p>
     */
    private static Path patchInstallerForMirror(Path installer) throws IOException {
        Path out = installer.getParent().resolve(
                installer.getFileName().toString().replace(".jar", "-mirror.jar"));
        boolean patched = false;
        try (ZipFile zip = new ZipFile(installer.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            var en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                zos.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream in = zip.getInputStream(e)) {
                    String name = e.getName();
                    if (!e.isDirectory()
                            && (name.equals("install_profile.json") || name.equals("version.json"))) {
                        String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        String replaced = s
                                .replace(OFFICIAL_MAVEN, BMCLAPI_MAVEN)
                                .replace("http://maven.neoforged.net/releases/", BMCLAPI_MAVEN)
                                .replace("https://maven.neoforged.net/releases", BMCLAPI_MAVEN)
                                .replace("https://libraries.minecraft.net/",
                                        "https://bmclapi2.bangbang93.com/libraries/")
                                .replace("https://resources.download.minecraft.net/",
                                        "https://bmclapi2.bangbang93.com/assets/");
                        patched |= !replaced.equals(s);
                        zos.write(replaced.getBytes(StandardCharsets.UTF_8));
                    } else {
                        in.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }
        }
        if (!patched) {
            Log.warn("No URL patched in NeoForge installer jar - mirror may not take effect");
        }
        return out;
    }

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
        Log.info("Running NeoForge installer with '{}'...", java);

        List<String> cmd = new ArrayList<>();
        cmd.add(java.toAbsolutePath().toString());
        cmd.add("-Dhttp.agent=curl/8.21.0");
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && proxyPort != null) {
            cmd.add("-Dhttps.proxyHost=" + proxyHost);
            cmd.add("-Dhttps.proxyPort=" + proxyPort);
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
                Log.warn("NeoForge installer exited with code {}", code);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Interrupted while running NeoForge installer", e);
        }
    }

    // ==================== 静态安装 / 归位 ====================

    private static JsonObject staticVersionJson(JsonObject installProfile, JsonObject embedded,
                                                String loader) throws IOException {
        if (embedded == null) {
            throw new IOException("NeoForge installer has no version.json; cannot install statically");
        }
        JsonObject out = embedded.deepCopy();
        out.remove("librariesFrom");
        if (!out.has("id")) out.addProperty("id", "neoforge-" + loader);
        if (installProfile != null && installProfile.get("libraries") instanceof JsonObject libs) {
            JsonArray arr = out.get("libraries") instanceof JsonArray a ? a : new JsonArray();
            for (var e : libs.entrySet()) {
                if (e.getValue() instanceof JsonObject o) arr.add(o);
            }
            out.add("libraries", arr);
        }
        ProcessorRecords.InstallProfile profile = ProcessorRecords.profile(installProfile);
        if (profile.minecraft() != null && profile.version() != null) {
            Log.debug("NeoForge install profile: {} for {}", profile.version(), profile.minecraft());
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