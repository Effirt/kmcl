package cd.cc.effirt.kinastminecraftlauncher.download.neoforge;

import module java.base;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser;
import cd.cc.effirt.kinastminecraftlauncher.util.FilesUtil;
import cd.cc.effirt.kinastminecraftlauncher.util.Http;
import cd.cc.effirt.kinastminecraftlauncher.util.ProgressBar;
import cd.cc.effirt.kinastminecraftlauncher.util.XmlUtil;
import cd.cc.effirt.util.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class NeoForgeInstaller {

    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String BMCLAPI_META = BMCLAPI_MAVEN + "net/neoforged/neoforge/maven-metadata.xml";
    private static final String OFFICIAL_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String OFFICIAL_META = OFFICIAL_MAVEN + "net/neoforged/neoforge/maven-metadata.xml";

    public static Path install(String mcVersion, String requestedLoader, String instanceName)
            throws IOException {
        String loader = (requestedLoader == null || requestedLoader.isBlank())
                ? resolveLatestLoader(mcVersion)
                : requestedLoader;
        Log.info("NeoForge Loader version: {}", loader);

        Path installer = downloadInstaller(loader);
        Path patched = patchInstallerForMirror(installer);
        runInstaller(patched);
        return mapToInstance(installedVersionDir(loader), instanceName, mcVersion, loader);
    }

    // ==================== 版本解析 ====================

    private static String resolveLatestLoader(String mcVersion) throws IOException {
        List<String> versions = fetchVersions(loaderPrefix(mcVersion));
        if (versions.isEmpty()) {
            throw new IOException("No NeoForge versions found for Minecraft " + mcVersion);
        }
        versions.sort(NeoForgeInstaller::compareVersions);
        return versions.getLast();
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
        for (String url : new String[]{BMCLAPI_META, OFFICIAL_META}) {
            try {
                List<String> all = XmlUtil.readVersionTags(Http.get(url));
                List<String> filtered = new ArrayList<>();
                for (String v : all) if (v.startsWith(prefix)) filtered.add(v);
                if (!filtered.isEmpty()) {
                    Log.info("Getting {} NeoForge versions...", filtered.size());
                    return filtered;
                }
                Log.warn("{} returned {} versions, none matched '{}'", url, all.size(), prefix);
            } catch (IOException e) {
                Log.warn("{} unreachable: {}", url, e.getMessage());
            }
        }
        return List.of();
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
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    // ==================== installer jar 下载 ====================

    private static Path downloadInstaller(String loader) throws IOException {
        String fileName = "neoforge-" + loader + "-installer.jar";
        Path target = KMCLPath.getKmclPath().resolve("cache").resolve(fileName);
        Files.createDirectories(target.getParent());

        String bmclUrl = BMCLAPI_MAVEN + "net/neoforged/neoforge/" + loader + "/" + fileName;
        String onUrl = OFFICIAL_MAVEN + "net/neoforged/neoforge/" + loader + "/" + fileName;
        Log.info("Downloading NeoForge installer...");
        try {
            Log.info("Trying official url...");
            try (ProgressBar pb = new ProgressBar()) {
                pb.setCustomText(fileName);
                pb.start();
                Http.getDownload(onUrl, target, pb);
            }
        } catch (IOException e) {
            Log.error("Failed to official url: {}", e.getMessage());
            Log.debug("", e);
            Log.info("Trying BMCL url...");
            try (ProgressBar pb = new ProgressBar()) {
                pb.setCustomText(fileName);
                pb.start();
                Http.getDownload(bmclUrl, target, pb);
            }
        }
        return target;
    }

    // ==================== patch installer 内的 URL ====================

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
                                .replace("http://maven.neoforged.net/releases/", BMCLAPI_MAVEN);
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
            Log.warn("No URL patched in installer jar — mirror may not take effect");
        }
        return out;
    }

    // ==================== 执行 installer ====================

    private static void runInstaller(Path installer) throws IOException {
        Path kmclRoot = KMCLPath.getKmclPath();
        Files.createDirectories(kmclRoot);

        Path profiles = kmclRoot.resolve("launcher_profiles.json");
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

        String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        if (!Files.isRegularFile(java)) {
            throw new IOException("Cannot find java at " + java);
        }

        Log.info("Running NeoForge installer...");
        ProcessBuilder pb = new ProcessBuilder(
                java.toAbsolutePath().toString(),
                "-Dhttp.agent=curl/8.21.0",
                "-XX:+UseCompactObjectHeaders",
                "-jar", installer.toAbsolutePath().toString(),
                "--installClient", kmclRoot.toAbsolutePath().toString()
        );

        pb.redirectErrorStream(false);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

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
                throw new IOException("NeoForge installer exited with code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Interrupted while running NeoForge installer", e);
        }
    }

    // ==================== 结果归位 ====================

    private static Path installedVersionDir(String loader) throws IOException {
        Path dir = KMCLPath.getKmclPath().resolve("versions").resolve("neoforge-" + loader);
        if (!Files.isDirectory(dir)) {
            throw new IOException("NeoForge installer did not create " + dir);
        }
        return dir;
    }

    private static Path mapToInstance(Path installedDir, String instanceName, String mcVersion, String loaderVersion)
            throws IOException {
        String installedName = installedDir.getFileName().toString();
        Path installedJson = installedDir.resolve(installedName + ".json");

        JsonObject neoforgeJson;
        try {
            neoforgeJson = JsonParser.parseString(
                    Files.readString(installedJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed NeoForge version JSON: " + installedJson, e);
        }

        JsonObject vanillaJson = loadVanillaJson(mcVersion);
        JsonObject merged = VersionJSONParser.mergeLoaderWithVanilla(
                neoforgeJson, vanillaJson, instanceName, mcVersion);
        merged.addProperty("kmcl.loader", "neoforge");
        merged.addProperty("kmcl.loaderVersion", loaderVersion);

        if (instanceName.equals(installedName)) {
            Files.createDirectories(installedDir.resolve("natives"));
            Files.writeString(installedJson, merged.toString(), StandardCharsets.UTF_8);
            return installedJson;
        }

        Path targetDir = VersionInstaller.versionDir(instanceName);
        if (Files.exists(targetDir)) FilesUtil.deleteRecursively(targetDir);
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
            throw new IOException("Vanilla version JSON not found: " + vanillaJson);
        }
        try {
            return JsonParser.parseString(
                    Files.readString(vanillaJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed vanilla version JSON: " + vanillaJson, e);
        }
    }
}