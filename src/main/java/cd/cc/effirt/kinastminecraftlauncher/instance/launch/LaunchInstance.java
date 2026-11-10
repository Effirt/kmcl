package cd.cc.effirt.kinastminecraftlauncher.instance.launch;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.VersionProvider;
import cd.cc.effirt.kinastminecraftlauncher.auth.Account;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountStorage;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountTypeEnum;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.Argument;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.Library;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.VersionInfo;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceInfoRecord;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceRegistry;
import cd.cc.effirt.kinastminecraftlauncher.util.JavaFinder;
import cd.cc.effirt.util.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;


public class LaunchInstance {
    private static final Path DEFAULT_MC_ROOT = KMCLPath.getKmclPath();
    private static final Pattern JWT_RA_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){1,4}");
    private static final String LOG4J_RESOURCE = "/kmcl-log4j2.xml";
    private static final String LOG4J_FILENAME = "kmcl-client.xml";

    public static int launch(String instanceName, Account acc) throws IOException {
        // 1. 从 registry 反查 mcVersion / loader
        InstanceInfoRecord instInfo = InstanceRegistry.get(instanceName);
        if (instInfo == null) {
            throw new IOException("Instance not found: " + instanceName);
        }
        String mcVersion = instInfo.minecraftVersion();
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IOException("Instance '" + instanceName + "' has no minecraftVersion in registry");
        }

        Path jsonFile = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve(mcVersion + ".json");
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("Instance JSON not found: " + jsonFile);
        }
        VersionInfo info = loadVersion(jsonFile, instInfo);
        if (info.mainClass() == null || info.mainClass().isBlank()) {
            throw new IOException("version JSON has no mainClass: " + jsonFile);
        }

        // 2. 实例目录 & 配置
        Path gameDir = VersionInstaller.instanceGameDir(instanceName);
        Files.createDirectories(gameDir);
        InstanceConfig.load(gameDir);
        String java = InstanceConfig.getJava();
        String arguments = InstanceConfig.getArguments();
        String jvmArguments = InstanceConfig.getJvmArguments();
        String maxMemory = InstanceConfig.getMaxMemory();
        String minMemory = InstanceConfig.getMinMemory();

        // 3. 变量表 & classpath
        Map<String, String> vars = buildVars(instanceName, gameDir, info, acc);
        List<String> classpath = buildClasspath(instanceName, mcVersion, info, instInfo);
        vars.put("classpath", String.join(File.pathSeparator, classpath));

        List<String> cmd = new ArrayList<>();
        if (java != null && !java.isEmpty()) cmd.add(java);
        else cmd.add(resolveJava(info).toString());

        boolean userXmx = maxMemory != null && !maxMemory.isEmpty();
        boolean userXms = minMemory != null && !minMemory.isEmpty();
        if (userXmx) cmd.add("-Xmx" + maxMemory);
        if (userXms) cmd.add("-Xms" + minMemory);

        boolean modern = info.arguments() != null;

        if (jvmArguments != null && !jvmArguments.isEmpty()) appendArgs(cmd, jvmArguments);

        // 4.1 JVM 参数
        if (modern) {
            for (Argument a : info.arguments().defaultUserJvm()) {
                if (!a.passes()) continue;
                for (String raw : argValues(a)) {
                    if (userXmx && raw.startsWith("-Xmx")) continue;
                    if (userXms && raw.startsWith("-Xms")) continue;
                    if (raw.startsWith("-Djava.library.path=")) continue;
                    cmd.add(substitute(raw, vars));
                }
            }
            for (Argument a : info.arguments().jvm()) {
                if (!a.passes()) continue;
                for (String raw : argValues(a)) {
                    if (raw.startsWith("-Djava.library.path=")) continue;
                    cmd.add(fixIgnoreList(substitute(raw, vars), mcVersion));
                }
            }
        } else {
            cmd.add("-Djava.library.path=" + vars.get("natives_directory"));
            cmd.add("-cp");
            cmd.add(vars.get("classpath"));
        }

        // 4.2 log4j 配置
        Path logConfig = prepareLog4jConfig();
        if (logConfig != null) {
            cmd.add("-Dlog4j.configurationFile=" + logConfig.toAbsolutePath().toUri());
        } else if (info.loggingClient() != null
                && info.loggingClient().file() != null
                && info.loggingClient().argument() != null) {
            Path logFile = KMCLPath.getKmclPath().resolve("assets").resolve("log_configs")
                    .resolve(info.loggingClient().file().id());
            if (Files.exists(logFile)) {
                cmd.add(info.loggingClient().argument()
                        .replace("${path}", logFile.toString()));
            }
        }

        cmd.add(info.mainClass());

        if (arguments != null && !arguments.isEmpty()) appendArgs(cmd, arguments);

        // 4.3 Game 参数
        if (modern) {
            for (Argument a : info.arguments().game()) {
                if (!a.passes()) continue;
                for (String raw : argValues(a)) {
                    cmd.add(substitute(raw, vars));
                }
            }
        } else if (info.minecraftArguments() != null) {
            for (String tok : info.minecraftArguments().split("\\s+")) {
                if (!tok.isEmpty()) cmd.add(substitute(tok, vars));
            }
        }

        // 5. 启动
        Log.info("Launching '{}' (Minecraft {}, {})...",
                instanceName, mcVersion, loaderLabel(instInfo));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.inheritIO();

        Log.debug("Launch argument: {}", cmd.stream()
                .map(s -> JWT_RA_PATTERN.matcher(s)
                        .replaceAll(m -> "*".repeat(m.group().length())))
                .toList());

        Process proc = pb.start();
        try {
            return proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroy();
            throw new IOException("Interrupted while waiting for game to exit", e);
        }
    }

    // ==================== helpers ====================

    /**
     * 读取实例 version json；若它仍然 {@code inheritsFrom} 原版（旧版 Launcher 写出的
     * Forge/NeoForge 实例），在内存里解析继承链后再使用。
     */
    static VersionInfo loadVersion(Path jsonFile, InstanceInfoRecord instInfo) throws IOException {
        VersionInfo info = VersionJSONParser.parse(jsonFile);
        if (!info.inherited()) return info;

        JsonObject raw;
        try (var r = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
            raw = JsonParser.parseReader(r).getAsJsonObject();
        }
        String parent = info.inheritsFrom();
        if (parent == null || parent.isBlank()) {
            throw new IOException("version JSON declares inheritsFrom but has no target: " + jsonFile);
        }
        Log.info("Resolving inheritsFrom '{}' for {}...", parent,
                instInfo == null ? jsonFile.getFileName() : instInfo.loader());
        JsonObject merged;
        try {
            merged = VersionJSONParser.resolve(raw, VersionDownloader::fetchVanillaJson);
        } catch (IOException e) {
            throw new IOException("Failed to resolve inheritsFrom '" + parent + "' of " + jsonFile, e);
        }
        return VersionJSONParser.parse(merged, info.mcVersion());
    }

    private static String loaderLabel(InstanceInfoRecord info) {
        String l = info.loader();
        if (l == null || l.equals("vanilla")) return "vanilla";
        String v = info.loaderVersion();
        return v == null ? l : l + " " + v;
    }

    private static Map<String, String> buildVars(String instanceName,
                                                 Path gameDir, VersionInfo info, Account acc)
            throws IOException {
        Map<String, String> v = new HashMap<>();
        String uuid = acc.uuid().toString().replace("-", "");

        if (acc.type() == AccountTypeEnum.MSA) {
            HttpClient http = MinecraftAuth.createHttpClient("KinastMinecraftLauncher/" + VersionProvider.getVer());
            JavaAuthManager mgr = JavaAuthManager.fromJson(http, acc.msaTokens());

            mgr.getChangeListeners().add(() -> {
                try {
                    MinecraftProfile profile = mgr.getMinecraftProfile().getCached();
                    if (profile == null) {
                        profile = mgr.getMinecraftProfile().getUpToDate();
                    }
                    Account updated = Account.msa(
                            profile.getName(),
                            profile.getId(),
                            JavaAuthManager.toJson(mgr));
                    new AccountStorage(KMCLPath.getKmclPath().resolve("accounts")).save(updated);
                } catch (Exception e) {
                    Log.error("Failed to persist refreshed token", e);
                }
            });

            String accessToken = mgr.getMinecraftToken().getUpToDate().getToken();

            v.put("auth_player_name", acc.name());
            v.put("auth_uuid", uuid);
            v.put("auth_access_token", accessToken);
            v.put("auth_session", "token:" + accessToken + ":" + uuid);
            String xuidVal = "0";
            try {
                String x = mgr.getXboxUserProfile().getUpToDate().getId();
                if (x != null && !x.isBlank()) xuidVal = x;
            } catch (Exception e) {
                Log.warn("Failed to obtain XUID, falling back to 0: {}", e.getMessage());
            }
            v.put("auth_xuid", xuidVal);
            v.put("user_type", "msa");
            v.put("user_properties", "{}");
        } else {
            v.put("auth_player_name", acc.name());
            v.put("auth_uuid", uuid);
            v.put("auth_access_token", "kmcl");
            v.put("auth_session", "token:kmcl:" + uuid);
            v.put("auth_xuid", "kmcl");
            v.put("user_type", "legacy");
            v.put("user_properties", "{}");
        }

        v.put("version_name", instanceName);
        v.put("version_type", info.type() == null ? "release" : info.type());
        v.put("game_directory", gameDir.toString());
        v.put("assets_root", KMCLPath.getKmclPath().resolve("assets").toString());
        v.put("game_assets", KMCLPath.getKmclPath().resolve("assets").resolve("virtual/legacy").toString());
        v.put("assets_index_name", info.assets() == null ? "legacy" : info.assets());
        // 新格式的 Forge / NeoForge 依赖库里的 natives（natives-*.jar），实例目录不一定有 natives/
        Path natives = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve("natives");
        if (!Files.isDirectory(natives)) {
            natives = KMCLPath.getKmclPath().resolve("libraries").resolve("natives");
        }
        Files.createDirectories(natives);
        v.put("natives_directory", natives.toString());
        v.put("launcher_name", "kmcl");
        v.put("launcher_version", VersionProvider.getVer());
        v.put("library_directory", DEFAULT_MC_ROOT.resolve("libraries").toString());
        v.put("classpath_separator", File.pathSeparator);
        v.put("clientid", "kmcl");

        return v;
    }

    private static List<String> buildClasspath(String instanceName, String mcVersion,
                                               VersionInfo info, InstanceInfoRecord instInfo) {
        Set<String> cp = new LinkedHashSet<>();
        Path libsDir = DEFAULT_MC_ROOT.resolve("libraries");
        boolean modern = info.arguments() != null;

        for (Library lib : info.libraries()) {
            if (!lib.isAllowed()) continue;
            if (!modern && isNativeLibrary(lib)) continue;

            Path p = resolveLibraryPath(lib, libsDir);
            if (p != null && Files.isRegularFile(p)) {
                cp.add(p.toString());
            }
        }

        // 新版 Forge / NeoForge 的 client 类来自 libraries（net.minecraft:client:*:srg），
        // 实例目录里本来就没有 <mcVersion>.jar，不要把它算成缺失。
        boolean loaderProvidesClient = "neoforge".equals(instInfo.loader()) || info.inherited();
        Path clientJar = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve(mcVersion + ".jar");
        if (Files.isRegularFile(clientJar)) {
            cp.add(clientJar.toString());
        } else if (!loaderProvidesClient) {
            Log.warn("Client jar not found: {}", clientJar);
        }
        return new ArrayList<>(cp);
    }

    private static boolean isNativeLibrary(Library lib) {
        if (lib.nativeArtifact() != null && Objects.requireNonNull(lib.nativeArtifact()).url() != null) return true;
        if (lib.classifier() != null && lib.classifier().startsWith("natives-")) return true;
        return lib.downloads() != null && lib.downloads().artifact() != null
                && lib.downloads().artifact().path() != null
                && lib.downloads().artifact().path().contains("-natives-");
    }

    private static Path resolveLibraryPath(Library lib, Path libsDir) {
        if (lib.downloads() != null && lib.downloads().artifact() != null
                && lib.downloads().artifact().path() != null
                && !lib.downloads().artifact().path().isBlank()) {
            return libsDir.resolve(lib.downloads().artifact().path());
        }
        if (lib.downloads() == null && lib.url() != null
                && lib.groupId() != null && lib.artifactId() != null
                && lib.version() != null) {
            return libsDir.resolve(lib.mavenPath());
        }
        // Forge 1.17+ 这类 “只有 Maven 坐标” 的库（已由安装器放进 libraries/）
        if (lib.downloads() == null && lib.name() != null
                && lib.groupId() != null && lib.artifactId() != null
                && lib.version() != null) {
            return libsDir.resolve(lib.mavenPath());
        }
        return null;
    }

    // ==================== Java ====================

    /**
     * 取一个满足 {@code info.javaVersion()} 的 JVM。
     *
     * <p>找不到正好相等的版本时按“就近”回退（与主流启动器一致）：</p>
     * <ul>
     *   <li>要求 Java 8 及以上：取能启动该版本的最新 Java（取不到更低版本）；</li>
     *   <li>要求更老（1.16.5 及以前的 Java 8 档）：取最接近的版本，低版本优先；</li>
     *   <li>新旧都不够/不可启动时，依次退到 JAVA_HOME → PATH → 系统 JVM，并给出警告。</li>
     * </ul>
     */
    private static Path resolveJava(VersionInfo info) {
        int required = (info.javaVersion() != null && info.javaVersion().majorVersion() > 0)
                ? info.javaVersion().majorVersion() : 21;

        List<JavaCandidate> found = scanJavaCandidates();
        Path exact = pick(found, required);
        if (exact != null) {
            Log.info("Use java{} from '{}'", required, exact);
            return exact;
        }

        Path best = pickNearest(found, required);
        if (best != null) {
            int actual = majorVersion(best);
            Log.warn("No java{} found, falling back to java{} at '{}'", required, actual, best);
            return best;
        }

        Path any = firstUsable(found);
        if (any != null) {
            Log.warn("No nearly-matching Java found, falling back to '{}'", any);
            return any;
        }

        try {
            Path fallback = JavaFinder.findAny();
            Log.warn("No java{} found, falling back to {}", required, fallback);
            return fallback;
        } catch (IOException e) {
            Log.warn("No java at all found: {}", e.getMessage());
            String sys = System.getProperty("java.home");
            if (sys != null && !sys.isBlank()) {
                return Path.of(sys, "bin", javaName());
            }
            throw new IllegalStateException(
                    "No Java runtime found. Please install JDK or set JAVA_HOME.");
        }
    }

    /** 已探测过的 java 可执行文件（路径 + 主版本号）。 */
    private record JavaCandidate(Path path, int major) {
    }

    /**
     * 依次扫描 JAVA_HOME、PATH 与常见安装目录，逐个探测主版本号。
     *
     * <p>常见目录覆盖 jdk-17 / jdk-21 这类顺序命名与 Temurin / GraalVM / 本机 JDK 安装。</p>
     */
    private static List<JavaCandidate> scanJavaCandidates() {
        List<JavaCandidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<Path> candidates = new ArrayList<>();
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "bin", javaName()));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir, javaName()));
                }
            }
        }
        candidates.addAll(commonJavaHomes());

        for (Path java : candidates) {
            Path p = java.toAbsolutePath().normalize();
            if (!seen.add(p.toString())) continue;
            if (!Files.isRegularFile(p)) continue;
            int v = majorVersion(p);
            if (v < 0) continue;
            out.add(new JavaCandidate(p, v));
        }
        return out;
    }

    /** 常见 Java 安装位置（Windows 的 Program Files、Linux 的 /usr/lib/jvm、macOS 的 /Library/Java 以及 SDKMAN）。 */
    private static List<Path> commonJavaHomes() {
        List<Path> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank()) {
                    roots.add(Path.of(v, "Java"));
                    roots.add(Path.of(v, "Eclipse Adoptium"));
                    roots.add(Path.of(v, "Zulu"));
                    roots.add(Path.of(v, "Microsoft"));
                }
            }
        } else if (os.contains("mac")) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            String sdkman = System.getenv("SDKMAN_DIR");
            if (sdkman != null && !sdkman.isBlank()) {
                roots.add(Path.of(sdkman, "candidates", "java"));
            }
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var s = Files.list(root)) {
                s.sorted().forEach(p -> {
                    out.add(p.resolve("bin").resolve(javaName()));
                    out.add(p.resolve("Contents").resolve("Home").resolve("bin").resolve(javaName()));
                });
            } catch (IOException _) {
                // 目录不可读就跳过
            }
        }
        return out;
    }

    private static Path pick(List<JavaCandidate> found, int required) {
        for (JavaCandidate c : found) {
            if (c.major() == required) return c.path();
        }
        return null;
    }

    /** 就近回退：Java 8 及以上取比要求新的里最老的，否则取比要求旧的里最新。 */
    private static Path pickNearest(List<JavaCandidate> found, int required) {
        JavaCandidate newer = null;
        JavaCandidate older = null;
        for (JavaCandidate c : found) {
            if (c.major() > required) {
                if (newer == null || c.major() < newer.major()) newer = c;
            } else if (c.major() < required) {
                if (older == null || c.major() > older.major()) older = c;
            }
        }
        if (newer != null) return newer.path();
        if (required >= 8) {
            Log.warn("Java{} or newer is required, but only older runtimes were found", required);
            return null;
        }
        return older == null ? null : older.path();
    }

    private static Path firstUsable(List<JavaCandidate> found) {
        for (JavaCandidate c : found) {
            if (c.major() >= 8) return c.path();
        }
        return null;
    }

    private static String javaName() {
        return JavaFinder.javaExeName();
    }

    private static int majorVersion(Path javaPath) {
        if (!Files.isRegularFile(javaPath)) return -1;
        try {
            Process p = new ProcessBuilder(javaPath.toString(), "-version")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int i = out.indexOf("version \"");
            if (i < 0) return -1;
            String rest = out.substring(i + 9);
            int e = rest.indexOf('"');
            if (e < 0) return -1;
            String[] parts = rest.substring(0, e).split("\\.");
            return parts[0].equals("1") && parts.length > 1
                    ? Integer.parseInt(parts[1]) : Integer.parseInt(parts[0]);
        } catch (Exception ex) {
            return -1;
        }
    }

    private static Path prepareLog4jConfig() {
        try (InputStream in = LaunchInstance.class.getResourceAsStream(LOG4J_RESOURCE)) {
            if (in == null) {
                Log.debug("Log4j config resource not found: {}", LOG4J_RESOURCE);
                return null;
            }
            Path dir = KMCLPath.getKmclPath().resolve("assets").resolve("log_configs");
            Files.createDirectories(dir);
            Path target = dir.resolve(LOG4J_FILENAME);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Log.warn("Failed to extract log4j config: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> argValues(Argument a) {
        return switch (a) {
            case Argument.Plain p -> List.of(p.value());
            case Argument.Conditional c -> c.values();
        };
    }

    private static String substitute(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) return template;
        StringBuilder sb = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, start);
            int end = template.indexOf('}', start);
            if (end < 0) {
                sb.append(template, start, template.length());
                break;
            }
            String key = template.substring(start + 2, end);
            String v = vars.get(key);
            sb.append(v == null ? "" : v);
            i = end + 1;
        }
        return sb.toString();
    }

    private static void appendArgs(List<String> cmd, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String tok : raw.trim().split("\\s+")) {
            if (!tok.isEmpty()) cmd.add(tok);
        }
    }

    /**
     * 确保 -DignoreList 包含 <mcVersion>.jar。
     * NeoForge 的 version.json 里写的是 ${version_name}.jar（= <instanceName>.jar），
     * 而实际 client jar（若存在）文件名是 <mcVersion>.jar。两者都不在 classpath 上，
     * 但 FML 会把 ignoreList 里的文件名当 Java 模块尝试加载——只要文件不存在就无害，
     * 文件存在（如旧版本下载残留）则会被忽略，避免与 minecraft 模块冲突。
     */
    private static String fixIgnoreList(String raw, String mcVersion) {
        String prefix = "-DignoreList=";
        if (!raw.startsWith(prefix)) return raw;
        String value = raw.substring(prefix.length());
        String clientJar = mcVersion + ".jar";
        for (String part : value.split(",")) {
            if (part.equals(clientJar)) return raw;
        }
        return prefix + value + "," + clientJar;
    }
}