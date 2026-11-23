package cd.cc.effirt.kinastminecraftlauncher.instance.launch;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.VersionProvider;
import cd.cc.effirt.kinastminecraftlauncher.auth.Account;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountStorage;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountTypeEnum;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.Argument;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.Library;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.VersionInfo;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceInfoRecord;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceRegistry;
import cd.cc.effirt.kinastminecraftlauncher.util.JavaFinder;
import cd.cc.effirt.auroralib.Log;
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
        Path gameDir = KMCLPath.getKmclPath().resolve("instances").resolve(instanceName);
        Files.createDirectories(gameDir);
        InstanceConfig.load(instanceName);
        String java = InstanceConfig.getJava();
        String arguments = InstanceConfig.getArguments();
        String jvmArguments = InstanceConfig.getJvmArguments();
        String maxMemory = InstanceConfig.getMaxMemory();
        String minMemory = InstanceConfig.getMinMemory();
        // 是否采用 version JSON 里的默认参数（kmcl.properties: defaultJvmArguments）
        boolean defaultJvmArguments = InstanceConfig.getDefaultJvmArguments();

        // 3. 变量表 & classpath
        Map<String, String> vars = buildVars(instanceName, gameDir, info, acc);
        List<String> classpath = buildClasspath(instanceName, mcVersion, info, instInfo);
        vars.put("classpath", String.join(File.pathSeparator, classpath));

        List<String> cmd = new ArrayList<>();
        Path javaExe;
        if (java != null && !java.isEmpty()) {
            javaExe = Path.of(java);
        } else {
            javaExe = resolveJava(info);
        }
        cmd.add(javaExe.toString());

        // Java 8 + UTF-8 控制台（chcp 65001）时，sun.stdout.encoding 是 "cp65001"，
        // 而 Java 8 的 Charset 不认识这个别名。Minecraft 1.13~1.16 自带的 log4j 2.8
        // 会拿它去 Charset.forName，直接抛 UnsupportedCharsetException: cp65001，
        // 导致 Console appender 建不出来、刷一屏 ERROR。显式覆盖成 UTF-8 即可。
        int javaMajor = majorVersion(javaExe);
        if (javaMajor > 0 && javaMajor < 9) {
            cmd.add("-Dsun.stdout.encoding=UTF-8");
            cmd.add("-Dsun.stderr.encoding=UTF-8");
        }

        boolean userXmx = maxMemory != null && !maxMemory.isEmpty();
        boolean userXms = minMemory != null && !minMemory.isEmpty();
        if (userXmx) cmd.add("-Xmx" + maxMemory);
        if (userXms) cmd.add("-Xms" + minMemory);

        boolean modern = info.arguments() != null;

        if (jvmArguments != null && !jvmArguments.isEmpty()) appendArgs(cmd, jvmArguments);

        // 4.1 JVM 参数
        if (modern) {
            // 1.16 及更早用 LWJGL 3.2，natives 是解压出来的 dll，必须从 java.library.path 加载；
            // 1.17+ 虽然能从 classpath 里的 natives-*.jar 直接取，但补上也无害。
            // 版本 JSON 自带的 -Djava.library.path 会在下面被跳过，这里统一补一次。
            cmd.add("-Djava.library.path=" + vars.get("natives_directory"));
            if (defaultJvmArguments) {
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
                for (Argument a : info.arguments().jvm()) {
                    if (!a.passes()) continue;
                    for (String raw : argValues(a)) {
                        if (raw.startsWith("-Djava.library.path=")) continue;
                        String value = substitute(raw, vars);
                        if (isJvmTuningFlag(value)) {
                            continue;
                        }
                        cmd.add(fixIgnoreList(value, mcVersion));
                    }
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

        // 关闭默认参数时，JSON 不再提供 -cp / -Djava.library.path，
        // 这两个属于“能启动”的必需品，启动器自己补上（可被用户 jvmArguments 覆盖）
        if (!defaultJvmArguments) ensureEssentialJvmArgs(cmd, vars);

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
        Log.info("Launching instance '{}' (Minecraft {}, {})...",
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
     * JVM 调优参数（{@code -Xmx} / {@code -Xms} / {@code -Xss} / {@code -XX:...} /
     * {@code -XstartOnFirstThread}）。这类参数在 {@code defaultJvmArguments=false} 时
     * 交给实例配置的 {@code jvmArguments} 决定。
     */
    private static boolean isJvmTuningFlag(String arg) {
        return arg != null && arg.startsWith("-X");
    }

    /**
     * 补齐启动必需的 JVM 参数：{@code -cp} 与 {@code -Djava.library.path}。
     *
     * <p>仅在 {@code defaultJvmArguments=false} 时调用。用户自己通过
     * {@code jvmArguments} 写出了这两个参数时不再重复添加。</p>
     */
    private static void ensureEssentialJvmArgs(List<String> cmd, Map<String, String> vars) {
        boolean hasClasspath = false;
        boolean hasLibraryPath = false;
        for (String s : cmd) {
            if (s.equals("-cp") || s.equals("-classpath") || s.startsWith("-Djava.class.path=")) {
                hasClasspath = true;
            } else if (s.startsWith("-Djava.library.path=")) {
                hasLibraryPath = true;
            }
        }
        String classpath = vars.get("classpath");
        if (!hasClasspath && classpath != null && !classpath.isBlank()) {
            cmd.add("-cp");
            cmd.add(classpath);
        }
        if (!hasLibraryPath) {
            cmd.add("-Djava.library.path=" + vars.get("natives_directory"));
        }
    }

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
            String xuidVal = "kmcl";
            try {
                String x = mgr.getXboxUserProfile().getUpToDate().getId();
                if (x != null && !x.isBlank()) xuidVal = x;
            } catch (Exception e) {
                Log.warn("Failed to obtain XUID: {}", e.getMessage());
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
        // 老格式（1.12 及更早 / 老版本 Forge）使用 ${game_directory} 而不是 ${gameDir}
        v.put("game_directory", gameDir.toString());
        v.put("gameDir", gameDir.toString());
        v.put("version", instanceName);
        v.put("assets_index_name", info.assets() == null ? "legacy" : info.assets());
        v.put("assets_index", v.get("assets_index_name"));
        v.put("assets_root", KMCLPath.getKmclPath().resolve("assets").toString());
        v.put("game_assets", KMCLPath.getKmclPath().resolve("assets").resolve("virtual/legacy").toString());
        // 旧版启动器用的额外别名（老 Forge / 1.7 之前的 json 里有引用）
        v.put("user_type", v.getOrDefault("user_type", "legacy"));
        v.put("user_type_alt", v.get("user_type"));
        v.put("user_properties", v.getOrDefault("user_properties", "{}"));
        v.put("user_properties_map", v.get("user_properties"));
        // 新格式的 Forge / NeoForge 依赖库里的 natives（natives-*.jar），实例目录不一定有 natives/
        Path natives = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName).resolve("natives");
        if (!Files.isDirectory(natives)) {
            natives = KMCLPath.getKmclPath().resolve("libraries").resolve("natives");
        }
        Files.createDirectories(natives);
        v.put("natives_directory", natives.toString());
        v.put("launcher_name", "kmcl");
        v.put("launcher_version", VersionProvider.getVer());
        v.put("library_directory", KMCLPath.getKmclPath().resolve("libraries").toString());
        v.put("classpath_separator", File.pathSeparator);
        v.put("clientid", "kmcl");
        return v;
    }

    private static List<String> buildClasspath(String instanceName, String mcVersion,
                                               VersionInfo info, InstanceInfoRecord instInfo) {
        Set<String> cp = new LinkedHashSet<>();
        Path libsDir = KMCLPath.getKmclPath().resolve("libraries");
        boolean modern = info.arguments() != null;

        // 同一 group:artifact[:classifier] 在 classpath 上只能有一个版本。
        //
        // 原版 JSON 与 loader（Fabric/NeoForge）会各自声明同一库的不同版本，
        // 例如原版给了 org.ow2.asm:asm:9.6、Fabric 给了 9.10.1；两者同时进 classpath 时
        // Fabric 的 LoaderUtil.verifyClasspath 会直接抛
        // “duplicate ASM classes found on classpath” 而启动失败。
        // 这里按坐标去重，保留版本号最高的那个（向后兼容、能满足 loader 要求）。
        Map<String, Library> chosen = new HashMap<>();
        if (modern) {
            for (Library lib : info.libraries()) {
                if (!lib.isAllowed()) continue;
                String key = libraryKey(lib);
                if (key == null || lib.version() == null) continue;
                Library prev = chosen.get(key);
                if (prev == null || compareVersions(lib.version(), prev.version()) > 0) {
                    chosen.put(key, lib);
                }
            }
        }

        for (Library lib : info.libraries()) {
            if (!lib.isAllowed()) continue;
            if (!modern && isNativeLibrary(lib)) continue;

            if (modern) {
                String key = libraryKey(lib);
                if (key != null) {
                    Library winner = chosen.get(key);
                    if (winner != null && winner != lib
                            && !Objects.equals(winner.version(), lib.version())) {
                        continue;   // 被更高版本的同一坐标取代
                    }
                }
            }

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

    /** 去重用的坐标键：{@code group:artifact[:classifier]}（不含版本）。 */
    private static String libraryKey(Library lib) {
        if (lib.groupId() == null || lib.artifactId() == null) return null;
        String classifier = (lib.classifier() == null || lib.classifier().isBlank())
                ? "" : ":" + lib.classifier();
        return lib.groupId() + ":" + lib.artifactId() + classifier;
    }

    /** 按点分段比较版本号（{@code 9.10.1 > 9.6}）；非数字段按字符串比较。 */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String va = i < pa.length ? pa[i] : "0";
            String vb = i < pb.length ? pb[i] : "0";
            int c;
            if (va.matches("\\d+") && vb.matches("\\d+")) {
                c = Integer.compare(Integer.parseInt(va), Integer.parseInt(vb));
            } else {
                c = va.compareTo(vb);
            }
            if (c != 0) return c;
        }
        return 0;
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
     * 取一个满足 {@code info.javaVersion()} 的 JVM
     */
    private static Path resolveJava(VersionInfo info) {
        int required = (info.javaVersion() != null) ? info.javaVersion().majorVersion() : 21;

        List<JavaCandidateRecord> found = scanJavaCandidates();
        Path exact = pick(found, required);
        if (exact != null) {
            Log.info("Using Java {} from '{}'", required, exact);
            return exact;
        }

        throw new IllegalStateException("No Java runtime found");
    }

    /**
     * 依次扫描 JAVA_HOME、PATH 与常见安装目录，逐个探测主版本号
     */
    private static List<JavaCandidateRecord> scanJavaCandidates() {
        List<JavaCandidateRecord> out = new ArrayList<>();
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

        for (Path java : candidates) {
            Path p = java.toAbsolutePath().normalize();
            if (!seen.add(p.toString())) continue;
            if (!Files.isRegularFile(p)) continue;
            int v = majorVersion(p);
            if (v < 0) continue;
            out.add(new JavaCandidateRecord(p, v));
        }
        return out;
    }

    private static Path pick(List<JavaCandidateRecord> found, int required) {
        for (JavaCandidateRecord c : found) {
            if (c.major() == required) return c.path();
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