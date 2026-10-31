package cd.cc.effirt.kinastminecraftlauncher.download.neoforge;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import cd.cc.effirt.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class NeoForgeInstaller {

    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String BMCLAPI_META = BMCLAPI_MAVEN + "net/neoforged/neoforge/maven-metadata.xml";
    private static final String OFFICIAL_MAVEN = "https://maven.neoforged.net/releases/";


    public static Path install(String mcVersion, String requestedLoader, String instanceName)
            throws IOException {
        if (isLegacy(mcVersion)) {
            throw new IOException("NeoForge for Minecraft " + mcVersion
                    + " uses net.neoforged:forge and is not supported yet");
        }

        String loader = (requestedLoader == null || requestedLoader.isBlank())
                ? resolveLatestLoader(mcVersion)
                : requestedLoader;
        Log.info("NeoForge Loader version: {}", loader);

        Path installer = downloadInstaller(loader);
        Path patched = patchInstallerForMirror(installer);
        runInstaller(patched);

        Path installedDir = findInstalledDir(loader);
        return mapToInstance(installedDir, instanceName, mcVersion);
    }

    // ==================== 版本解析 ====================

    private static boolean isLegacy(String mcVersion) {
        String[] p = mcVersion.split("\\.");
        if (p.length < 2 || !"1".equals(p[0])) return false;
        try {
            int minor = Integer.parseInt(p[1]);
            if (minor < 20) return true;
            if (minor == 20) {
                int patch = p.length >= 3 ? Integer.parseInt(p[2]) : 0;
                return patch <= 1;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String resolveLatestLoader(String mcVersion) throws IOException {
        String prefix = loaderPrefix(mcVersion);
        List<String> versions = fetchVersions(prefix);
        if (versions.isEmpty()) {
            throw new IOException("No NeoForge Loader for Minecraft " + mcVersion
                    + " (prefix '" + prefix + "')");
        }
        versions.sort(NeoForgeInstaller::compareVersions);
        String latest = versions.getLast();
        Log.info("Resolved NeoForge {} -> {}", mcVersion, latest);
        return latest;
    }

    private static String loaderPrefix(String mcVersion) {
        String base = mcVersion.startsWith("1.") ? mcVersion.substring(2) : mcVersion;
        String[] parts = base.split("\\.");
        if (parts.length == 1) {
            return base + ".0.";
        }
        if (parts.length == 2 && !mcVersion.startsWith("1.")) {
            return base + ".0.";
        }
        return base + ".";
    }

    private static List<String> fetchVersions(String prefix) {
        try {
            String xml = Http.get(BMCLAPI_META);
            List<String> all = parseMavenMetadata(xml);
            List<String> filtered = new ArrayList<>();
            for (String v : all) if (v.startsWith(prefix)) filtered.add(v);
            if (!filtered.isEmpty()) {
                Log.info("Got {} NeoForge versions from BMCLAPI", filtered.size());
                return filtered;
            }
            Log.warn("BMCLAPI metadata returned {} versions, none matched '{}'",
                    all.size(), prefix);
        } catch (IOException e) {
            Log.warn("BMCLAPI metadata unreachable: {}", e.getMessage());
        }
        return List.of();
    }

    private static List<String> parseMavenMetadata(String xml) {
        List<String> out = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var doc = dbf.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var nodes = doc.getElementsByTagName("version");
            for (int i = 0; i < nodes.getLength(); i++) {
                String v = nodes.item(i).getTextContent().trim();
                if (!v.isEmpty()) out.add(v);
            }
        } catch (Exception e) {
            Log.warn("Failed to parse maven-metadata.xml: {}", e.getMessage());
        }
        return out;
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]"), pb = b.split("[.\\-]");
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
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== installer jar 下载 ====================

    private static Path downloadInstaller(String loader) throws IOException {
        String fileName = "neoforge-" + loader + "-installer.jar";
        Path target = KMCLPath.getKmclPath().resolve("cache").resolve(fileName);
        Files.createDirectories(target.getParent());

        String bmclUrl = BMCLAPI_MAVEN + "net/neoforged/neoforge/" + loader + "/" + fileName;
        Log.info("Downloading NeoForge installer from BMCLAPI...");
        try (ProgressBar pb = new ProgressBar()) {
            pb.setCustomText(fileName);
            pb.start();
            Http.getDownload(bmclUrl, target, pb);
        }
        return target;
    }

    // ==================== patch installer 内的 URL ====================

    /**
     * 解压 installer jar，把 install_profile.json / version.json 里的URL
     */
    private static Path patchInstallerForMirror(Path installer) throws IOException {
        Path work = Files.createTempDirectory("nf-mirror-");
        try {
            try (ZipFile zip = new ZipFile(installer.toFile())) {
                var en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    Path out = work.resolve(e.getName()).normalize();
                    if (!out.startsWith(work)) continue;
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            boolean patched = false;
            for (String f : new String[]{"install_profile.json", "version.json"}) {
                Path p = work.resolve(f);
                if (!Files.exists(p)) continue;
                String s = Files.readString(p, StandardCharsets.UTF_8);
                String replaced = s
                        .replace(OFFICIAL_MAVEN, BMCLAPI_MAVEN)
                        .replace("http://maven.neoforged.net/releases/", BMCLAPI_MAVEN);
                if (!replaced.equals(s)) {
                    Files.writeString(p, replaced, StandardCharsets.UTF_8);
                    patched = true;
                    Log.info("Patched {} -> BMCLAPI mirror", f);
                }
            }
            if (!patched) {
                Log.warn("No URL patched in installer jar — mirror may not take effect");
            }

            Path out = installer.getParent().resolve(
                    installer.getFileName().toString().replace(".jar", "-mirror.jar"));
            Files.deleteIfExists(out);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out));
                 Stream<Path> walk = Files.walk(work)) {
                for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = work.relativize(f).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(rel));
                    zos.write(Files.readAllBytes(f));
                    zos.closeEntry();
                }
            }
            return out;
        } finally {
            try (Stream<Path> s = Files.walk(work)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    // ==================== 执行 installer ====================

    private static void runInstaller(Path installer) throws IOException {
        Path kmclRoot = KMCLPath.getKmclPath();
        Files.createDirectories(kmclRoot);

        // installer 硬性要求 launcher_profiles.json
        Path profiles = kmclRoot.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Files.writeString(profiles, """
                    {
                      "profiles": {},
                      "settings": {},
                      "version": 3
                    }
                    """, StandardCharsets.UTF_8);
            Log.info("Created minimal launcher_profiles.json for installer");
        }

        String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        if (!Files.isRegularFile(java)) {
            throw new IOException("Cannot find java at " + java);
        }

        Log.info("Running patched NeoForge installer (may take several minutes)...");
        ProcessBuilder pb = new ProcessBuilder(
                java.toAbsolutePath().toString(),
                "-Dhttp.agent=curl/8.21.0",
                "-jar", installer.toAbsolutePath().toString(),
                "--installClient", kmclRoot.toAbsolutePath().toString()
        );

        Path installerLog = KMCLPath.getKmclPath().resolve("cache")
                .resolve("neoforge-installer.log");

        pb.redirectErrorStream(true);

        boolean debug = Log.isDebugEnabled();
        if (debug) {
            pb.inheritIO();
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.to(installerLog.toFile()));
        }

        // 代理传递（如果当前 JVM 配了代理）
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && proxyPort != null) {
            pb.environment().put("JAVA_TOOL_OPTIONS",
                    "-Dhttps.proxyHost=" + proxyHost + " -Dhttps.proxyPort=" + proxyPort);
        }

        Process proc = pb.start();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("NeoForge installer exited with code " + code
                        + " (see installer output above)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Interrupted while running NeoForge installer", e);
        }
    }

    // ==================== 结果归位 ====================

    private static Path findInstalledDir(String loader) throws IOException {
        Path versionsDir = KMCLPath.getKmclPath().resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            throw new IOException("versions/ not found after installer run: " + versionsDir);
        }
        Path direct = versionsDir.resolve("neoforge-" + loader);
        if (Files.isDirectory(direct)) return direct;
        try (Stream<Path> s = Files.list(versionsDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains(loader))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Cannot find installed NeoForge version dir for " + loader));
        }
    }

    private static Path mapToInstance(Path installedDir, String instanceName, String mcVersion)
            throws IOException {
        String installedName = installedDir.getFileName().toString();
        Path installedJson = installedDir.resolve(installedName + ".json");
        if (!Files.isRegularFile(installedJson)) {
            try (Stream<Path> s = Files.list(installedDir)) {
                installedJson = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .findFirst()
                        .orElseThrow(() -> new IOException("No version JSON in " + installedDir));
            }
        }

        JsonObject neoforgeJson;
        try {
            neoforgeJson = JsonParser.parseString(
                    Files.readString(installedJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed NeoForge version JSON: " + installedJson, e);
        }

        JsonObject vanillaJson = loadVanillaJson(mcVersion);
        JsonObject merged = mergeWithVanilla(neoforgeJson, vanillaJson, instanceName, mcVersion);

        if (instanceName.equals(installedName)) {
            Files.createDirectories(installedDir.resolve("natives"));
            Files.writeString(installedJson, merged.toString(), StandardCharsets.UTF_8);
            return installedJson;
        }

        Path targetDir = VersionInstaller.versionDir(instanceName);
        if (Files.exists(targetDir)) deleteRecursively(targetDir);
        Files.createDirectories(targetDir.getParent());
        Files.move(installedDir, targetDir, StandardCopyOption.REPLACE_EXISTING);

        Path targetJson = targetDir.resolve(instanceName + ".json");
        Path oldJson = targetDir.resolve(installedName + ".json");
        if (!oldJson.equals(targetJson) && Files.exists(oldJson)) {
            Files.deleteIfExists(oldJson);
        }

        Files.createDirectories(targetDir.resolve("natives"));
        Files.writeString(targetJson, merged.toString(), StandardCharsets.UTF_8);
        return targetJson;
    }

    private static JsonObject loadVanillaJson(String mcVersion) throws IOException {
        Path vanillaJson = KMCLPath.getKmclPath()
                .resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
        if (!Files.isRegularFile(vanillaJson)) {
            throw new IOException("Vanilla version JSON not found: " + vanillaJson
                    + " (run a vanilla install of " + mcVersion + " first)");
        }
        try {
            return JsonParser.parseString(
                    Files.readString(vanillaJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed vanilla version JSON: " + vanillaJson, e);
        }
    }

    private static JsonObject mergeWithVanilla(JsonObject neoforge, JsonObject vanilla,
                                               String instanceName, String mcVersion) {
        JsonObject merged = new JsonObject();
        copyIfPresent(vanilla, merged, "assetIndex");
        copyIfPresent(vanilla, merged, "assets");
        copyIfPresent(vanilla, merged, "downloads");
        copyIfPresent(vanilla, merged, "type");
        copyIfPresent(vanilla, merged, "logging");
        copyIfPresent(vanilla, merged, "javaVersion");

        merged.addProperty("id", instanceName);
        if (neoforge.has("mainClass")) merged.add("mainClass", neoforge.get("mainClass"));
        if (neoforge.has("javaVersion")) merged.add("javaVersion", neoforge.get("javaVersion"));

        JsonArray libs = new JsonArray();
        if (vanilla.get("libraries") instanceof JsonArray vl) libs.addAll(vl);
        if (neoforge.get("libraries") instanceof JsonArray nl) libs.addAll(nl);
        merged.add("libraries", libs);

        JsonObject args = new JsonObject();
        args.add("game", mergeArgs(vanilla, neoforge, "game"));
        args.add("jvm", mergeArgs(vanilla, neoforge, "jvm"));
        merged.add("arguments", args);

        if (vanilla.has("minecraftArguments")) {
            merged.add("minecraftArguments", vanilla.get("minecraftArguments"));
        }

        merged.addProperty("kmcl.minecraftVersion", mcVersion);
        return merged;
    }

    private static JsonArray mergeArgs(JsonObject a, JsonObject b, String key) {
        JsonArray out = new JsonArray();
        addArgs(out, a, key);
        addArgs(out, b, key);
        return out;
    }

    private static void addArgs(JsonArray out, JsonObject src, String key) {
        if (src.get("arguments") instanceof JsonObject args
                && args.get(key) instanceof JsonArray arr) {
            out.addAll(arr);
        }
    }

    private static void copyIfPresent(JsonObject src, JsonObject dst, String key) {
        if (src.has(key) && !src.get(key).isJsonNull()) {
            dst.add(key, src.get(key));
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}