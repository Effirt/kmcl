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
            throw new IOException("No NeoForge versions found for Minecraft " + mcVersion
                    + " (checked prefix " + expectedPrefix(mcVersion) + ")");
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

    /**
     * NeoForge 版本号与 MC 版本的对应关系。
     *
     * <ul>
     *   <li>旧版本号方案（1.x）：{@code 1.20.4} → {@code 20.4.x}；
     *       {@code 1.21} → {@code 21.0.x}；{@code 1.21.8} → {@code 21.8.x}</li>
     *   <li>新版本号方案（26.x）：{@code 26.2} → {@code 26.2.0.x}；
     *       {@code 26.1.2} → {@code 26.1.2.x}；{@code 26.3} → {@code 26.3.0.x}</li>
     * </ul>
     *
     * <p>NeoForge 的版本号 = MC 版本的“数字前缀” + 构建号；前缀必须逐段相等，
     * 因此 {@code 26.1} 不会误匹配到 {@code 26.1.2.x}，{@code 1.21} 也不会匹配 {@code 21.1.x}。</p>
     */
    static boolean matches(String neoVersion, String mcVersion) {
        List<String> expected = expectedPrefix(mcVersion);
        if (expected == null || expected.isEmpty()) return false;

        List<String> parts = numericPrefix(neoVersion);
        // 至少要比前缀多出一段构建号
        if (parts.size() < expected.size() + 1) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!parts.get(i).equals(expected.get(i))) return false;
        }
        return true;
    }

    /**
     * MC 版本 → NeoForge 版本号的数字前缀。
     *
     * @return 无法解析（例如快照/无效版本）时返回 {@code null}
     */
    static List<String> expectedPrefix(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return null;
        String v = mcVersion.trim();

        if (!v.startsWith("1.")) {
            // 新方案：26.1 / 26.2 / 26.1.2 ...，缺 patch 时补 0（26.2 → 26.2.0）
            List<String> seg = numericPrefix(v);
            if (seg.size() < 2) return null;
            while (seg.size() < 3) seg.add("0");
            return seg;
        }

        // 旧方案：1.20.4 → 20.4；1.21 → 21.0
        List<String> seg = numericPrefix(v.substring(2));
        if (seg.isEmpty()) return null;
        while (seg.size() < 2) seg.add("0");
        return seg;
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
        Log.info("Getting NeoForge loader list...");
        // 先官方，再 BMCLAPI 镜像
        for (String url : new String[]{OFFICIAL_META, BMCLAPI_META}) {
            try (ProgressBar pb = new ProgressBar()) {
                pb.setCustomText("maven-metadata.xml");
                pb.start();
                List<String> all = XmlUtil.readVersionTags(Http.get(url, pb));
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

    /** 自然升序：数字段逐段比较，数字相同时正式版大于预发布版。 */
    private static int compareVersions(String a, String b) {
        List<String> pa = numericPrefix(a);
        List<String> pb = numericPrefix(b);
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
        try (ProgressBar prb = new ProgressBar()) {
            prb.setProgressBar(false);
            prb.setSpinner(true);
            prb.setCustomText("Running NeoForge installer...");
            prb.start();

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