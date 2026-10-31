package cd.cc.effirt.kinastminecraftlauncher.launch;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.VersionProvider;
import cd.cc.effirt.kinastminecraftlauncher.auth.Account;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountStorage;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountTypeEnum;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.Argument;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.Library;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser.VersionInfo;
import cd.cc.effirt.util.Log;
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
        // 1. 读 JSON
        Path jsonFile = VersionInstaller.versionDir(instanceName).resolve(instanceName + ".json");
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("Instance not found: " + instanceName);
        }
        VersionInfo info = VersionJSONParser.parse(jsonFile);
        if (info.mainClass() == null || info.mainClass().isBlank()) {
            throw new IOException("version JSON has no mainClass: " + jsonFile);
        }

        Path instDir = VersionInstaller.versionDir(instanceName);
        InstanceConfig.load(instDir);
        String java = InstanceConfig.getJava();
        String arguments = InstanceConfig.getArguments();
        String jvmArguments = InstanceConfig.getJvmArguments();
        String maxMemory = InstanceConfig.getMaxMemory();
        String minMemory = InstanceConfig.getMinMemory();

        // 2. 工作目录 & 变量表
        Path gameDir = VersionInstaller.instanceGameDir(instanceName);
        Files.createDirectories(gameDir);
        Map<String, String> vars = buildVars(instanceName, gameDir, info, acc);

        // 3. classpath
        List<String> classpath = buildClasspath(instanceName, info);
        vars.put("classpath", String.join(File.pathSeparator, classpath));

        List<String> cmd = new ArrayList<>();
        if (java != null && !java.isEmpty()) cmd.add(java);
        else cmd.add(resolveJava(info).toString());

        boolean userXmx = maxMemory != null && !maxMemory.isEmpty();
        boolean userXms = minMemory != null && !minMemory.isEmpty();
        if (userXmx) cmd.add("-Xmx" + maxMemory);
        if (userXms) cmd.add("-Xms" + minMemory);

        boolean modern = info.arguments() != null;

        // 4.1 JVM 参数
        if (modern) {
            // 1) default-user-jvm：只在用户没覆盖时采用
            for (Argument a : info.arguments().defaultUserJvm()) {
                if (!a.passes()) continue;
                for (String raw : argValues(a)) {
                    if (userXmx && raw.startsWith("-Xmx")) continue;   // 用户已指定才跳过
                    if (userXms && raw.startsWith("-Xms")) continue;
                    if (raw.startsWith("-Djava.library.path=")) continue;
                    cmd.add(substitute(raw, vars));
                }
            }
            // 2) jvm：启动器必备，全部保留
            for (Argument a : info.arguments().jvm()) {
                if (!a.passes()) continue;
                for (String raw : argValues(a)) {
                    if (raw.startsWith("-Djava.library.path=")) continue;
                    cmd.add(substitute(raw, vars));
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
            cmd.add("-Dlog4j.configurationFile=" + logConfig.toAbsolutePath());
        } else if (info.loggingClient() != null
                && info.loggingClient().file() != null
                && info.loggingClient().argument() != null) {
            Path logFile = VersionInstaller.assetsDir().resolve("log_configs")
                    .resolve(info.loggingClient().file().id());
            if (Files.exists(logFile)) {
                cmd.add(info.loggingClient().argument()
                        .replace("${path}", logFile.toString()));
            }
        }

        if (jvmArguments != null && !jvmArguments.isEmpty()) appendArgs(cmd, jvmArguments);

        cmd.add(info.mainClass());

        // 4.3 Game 参数 —— 同样 !a.passes()
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

        if (arguments != null && !arguments.isEmpty()) appendArgs(cmd, arguments);

        // 5. 启动
        Log.info("Launching '{}' (Minecraft {})...", instanceName, info.mcVersion());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.inheritIO();

        Log.debug("Launch argument: {}", cmd.stream()
                .map(s -> JWT_RA_PATTERN.matcher(s)
                        .replaceAll(m -> {
                            String t = m.group();
                            return t.substring(0, 5) + "*".repeat(t.length() - 5);
                        }))
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

    private static Map<String, String> buildVars(String instanceName, Path gameDir,
                                                 VersionInfo info, Account acc) throws IOException {
        Map<String, String> v = new HashMap<>();
        String uuid = acc.uuid().toString().replace("-", "");

        if (acc.type() == AccountTypeEnum.MSA) {
            HttpClient http = MinecraftAuth.createHttpClient("KinastMinecraftLauncher/" + VersionProvider.getVer());
            JavaAuthManager mgr = JavaAuthManager.fromJson(http, acc.msaTokens());

            mgr.getChangeListeners().add(() -> {
                try {
                    MinecraftProfile profile = mgr.getMinecraftProfile().getCached();
                    if (profile == null) {
                        profile = mgr.getMinecraftProfile().getUpToDate();   // Fallback
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
            v.put("auth_session", "token:" + accessToken + ":" + uuid);// buildVars 里
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

        v.put("version_name", info.mcVersion());
        v.put("version_type", info.type() == null ? "release" : info.type());
        v.put("game_directory", gameDir.toString());
        v.put("assets_root", VersionInstaller.assetsDir().toString());
        v.put("game_assets", VersionInstaller.assetsDir().resolve("virtual/legacy").toString());
        v.put("assets_index_name", info.assets() == null ? "legacy" : info.assets());
        v.put("natives_directory", VersionInstaller.versionDir(instanceName).resolve("natives").toString());
        v.put("launcher_name", "kmcl");
        v.put("launcher_version", VersionProvider.getVer());
        v.put("library_directory", DEFAULT_MC_ROOT.resolve("libraries").toString());
        v.put("classpath_separator", File.pathSeparator);
        v.put("clientid", "kmcl");

        return v;
    }

    private static List<String> buildClasspath(String instanceName, VersionInfo info) {
        List<String> cp = new ArrayList<>();
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

        Path clientJar = VersionInstaller.versionDir(instanceName).resolve("client.jar");
        if (Files.isRegularFile(clientJar)) {
            cp.add(clientJar.toString());
        }
        return cp;
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
        return null;
    }

    // 2. 方法签名
    private static Path resolveJava(VersionInfo info) {
        int required = (info.javaVersion() != null && info.javaVersion().majorVersion() > 0)
                ? info.javaVersion().majorVersion() : 21;
        String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            Path c = Path.of(home, "bin", javaName);
            if (majorVersion(c) == required) {
                Log.info("Use java{} from '{}'", required, c);
                return c;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path c = Path.of(dir, javaName);
                if (majorVersion(c) == required) {
                    Log.info("Use java{} from '{}'", required, c);
                    return c;
                }
            }
        }
        Path fallback = Path.of(System.getProperty("java.home"), "bin", javaName);
        Log.warn("No Java {} found, falling back to {}",
                required, fallback);
        return fallback;
    }

    private static int majorVersion(Path javaPath) {
        if (!Files.isRegularFile(javaPath)) return -1;
        try {
            Process p = new ProcessBuilder(javaPath.toString(), "-version")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
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
            Path dir = VersionInstaller.assetsDir().resolve("log_configs");
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
}