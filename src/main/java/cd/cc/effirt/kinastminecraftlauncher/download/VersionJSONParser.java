package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


public final class VersionJSONParser {

    // =================== 入口 ===================

    public static VersionInfo parse(Path file) throws IOException {
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r).getAsJsonObject());
        } catch (JsonParseException e) {
            Log.error("Malformed version JSON: {}", file, e);
            throw e;
        }
    }

    public static VersionInfo parse(JsonObject root) {
        var loggingRoot = root.getAsJsonObject("logging");
        var loggingClient = loggingRoot == null ? null
                : Logging.of(loggingRoot.getAsJsonObject("client"));
        return new VersionInfo(
                str(root, "id"),
                str(root, "type"),
                str(root, "mainClass"),
                str(root, "assets"),
                str(root, "minecraftArguments"),
                resolveMcVersion(root),
                AssetIndex.of(root.getAsJsonObject("assetIndex")),
                Downloads.of(root.getAsJsonObject("downloads")),
                JavaVersion.of(root.getAsJsonObject("javaVersion")),
                Library.ofAll(root.getAsJsonArray("libraries")),
                Arguments.of(root.getAsJsonObject("arguments")),
                loggingClient
        );
    }

    private static String resolveMcVersion(JsonObject root) {
        var kmcl = root.get("kmcl.minecraftVersion");
        if (kmcl != null && !kmcl.isJsonNull()) return kmcl.getAsString();

        var inherits = root.get("inheritsFrom");
        if (inherits != null && !inherits.isJsonNull()) return inherits.getAsString();

        return str(root, "id");
    }

    public record FeatureContext(
            boolean demoUser,
            boolean customResolution,
            boolean quickPlaysSupport,
            boolean quickPlaySingleplayer,
            boolean quickPlayMultiplayer,
            boolean quickPlayRealms
    ) {
        public static final FeatureContext NONE =
                new FeatureContext(false, false, false, false, false, false);
    }

    // =================== 顶层数据 ===================

    public record VersionInfo(
            String id,
            String type,
            String mainClass,
            String assets,
            String minecraftArguments,
            String mcVersion,   // 旧版；null 表示现代格式
            AssetIndex assetIndex,
            Downloads downloads,
            JavaVersion javaVersion,
            List<Library> libraries,
            Arguments arguments,          // 新版；null 表示旧版
            Logging loggingClient
    ) {
        public VersionInfo {
            libraries = libraries == null ? List.of() : List.copyOf(libraries);
        }

    }

    public record AssetIndex(String id, String url, String sha1, long size, long totalSize) {
        static AssetIndex of(JsonObject o) {
            return o == null ? null : new AssetIndex(str(o, "id"), str(o, "url"), str(o, "sha1"),
                    lng(o, "size"), lng(o, "totalSize"));
        }
    }

    public record Downloads(Artifact client, Artifact server,
                            Artifact clientMappings, Artifact serverMappings) {
        static Downloads of(JsonObject o) {
            return o == null ? null
                    : new Downloads(Artifact.of(o.getAsJsonObject("client")),
                    Artifact.of(o.getAsJsonObject("server")),
                    Artifact.of(o.getAsJsonObject("client_mappings")),
                    Artifact.of(o.getAsJsonObject("server_mappings")));
        }
    }

    public record Artifact(String path, String url, String sha1, long size) {
        static Artifact of(JsonObject o) {
            return o == null ? null : new Artifact(str(o, "path"), str(o, "url"), str(o, "sha1"), lng(o, "size"));
        }

    }

    public record JavaVersion(String component, int majorVersion) {
        static JavaVersion of(JsonObject o) {
            return o == null ? null : new JavaVersion(str(o, "component"), (int) lng(o, "majorVersion"));
        }
    }

    public record Logging(String argument, LoggingFile file, String type) {

        public record LoggingFile(String id, String sha1, String url, long size) {
            static LoggingFile of(JsonObject o) {
                return o == null ? null
                        : new LoggingFile(str(o, "id"), str(o, "sha1"),
                        str(o, "url"), lng(o, "size"));
            }
        }

        static Logging of(JsonObject o) {
            return o == null ? null
                    : new Logging(str(o, "argument"),
                    LoggingFile.of(o.getAsJsonObject("file")),
                    str(o, "type"));
        }
    }

    // =================== libraries ===================

    public record Library(
            String name,
            String groupId, String artifactId, String version,
            String classifier, String extension,
            String url,
            LibraryDownloads downloads,
            Map<String, String> natives,
            List<Rule> rules,
            Extract extract
    ) {
        public Library {
            extension = extension == null || extension.isEmpty() ? "jar" : extension;
            natives = natives == null ? Map.of() : Map.copyOf(natives);
            rules = rules == null ? null : List.copyOf(rules);
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        public boolean isAllowed() {
            return Rule.evaluate(rules);
        }

        public String nativeClassifier() {
            return natives.get(Rule.currentOs());
        }

        public Artifact nativeArtifact() {
            var c = nativeClassifier();
            return (c == null || downloads == null || downloads.classifiers == null)
                    ? null
                    : downloads.classifiers.get(c);
        }

        public String mavenPath() {
            var sb = new StringBuilder(groupId.replace('.', '/'))
                    .append('/').append(artifactId)
                    .append('/').append(version)
                    .append('/').append(artifactId).append('-').append(version);
            if (classifier != null && !classifier.isEmpty()) sb.append('-').append(classifier);
            return sb.append('.').append(extension).toString();
        }

        static List<Library> ofAll(JsonArray arr) {
            if (arr == null) return List.of();
            var out = new ArrayList<Library>(arr.size());
            for (var el : arr) if (el instanceof JsonObject o) out.add(of(o));
            return out;
        }

        static Library of(JsonObject o) {
            if (o == null) return null;

            String groupId = null, artifactId = null, version = null, classifier = null;
            String extension = "jar";

            var name = str(o, "name");
            if (name != null) {
                var n = name;
                var at = n.indexOf('@');
                if (at >= 0) {
                    extension = n.substring(at + 1);
                    n = n.substring(0, at);
                }
                var parts = n.split(":");
                if (parts.length >= 3) {
                    groupId = parts[0];
                    artifactId = parts[1];
                    version = parts[2];
                    if (parts.length >= 4) classifier = parts[3];
                }
            }

            Map<String, String> natives = null;
            if (o.get("natives") instanceof JsonObject no) {
                natives = new HashMap<>();
                for (var e : no.entrySet()) natives.put(e.getKey(), e.getValue().getAsString());
            }

            return new Library(
                    name, groupId, artifactId, version, classifier, extension,
                    str(o, "url"),
                    LibraryDownloads.of(o.getAsJsonObject("downloads")),
                    natives,
                    Rule.ofAll(o.getAsJsonArray("rules")),
                    Extract.of(o.getAsJsonObject("extract")));
        }
    }

    public record LibraryDownloads(Artifact artifact, Map<String, Artifact> classifiers) {
        static LibraryDownloads of(JsonObject o) {
            if (o == null) return null;
            Map<String, Artifact> cls = null;
            if (o.get("classifiers") instanceof JsonObject co) {
                cls = new HashMap<>();
                for (var e : co.entrySet())
                    cls.put(e.getKey(), Artifact.of(e.getValue().getAsJsonObject()));
            }
            return new LibraryDownloads(Artifact.of(o.getAsJsonObject("artifact")), cls);
        }
    }

    public record Extract(List<String> exclude) {
        public Extract {
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }

        static Extract of(JsonObject o) {
            if (o == null) return null;
            List<String> ex = null;
            if (o.get("exclude") instanceof JsonArray a) {
                ex = new ArrayList<>(a.size());
                for (var el : a) ex.add(el.getAsString());
            }
            return new Extract(ex);
        }
    }

    // =================== rules ===================

    public record Rule(
            String action,
            String osName, String osArch, String osVersion,
            String osVersionMin, String osVersionMax,
            boolean demoUser, boolean customResolution,
            boolean quickPlaysSupport, boolean quickPlaySingleplayer,
            boolean quickPlayMultiplayer, boolean quickPlayRealms
    ) {
        public Rule {
            action = action == null ? "allow" : action;
        }

        private static int compareVersion(String a, String b) {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int va = i < pa.length ? parseIntOr0(pa[i]) : 0;
                int vb = i < pb.length ? parseIntOr0(pb[i]) : 0;
                if (va != vb) return Integer.compare(va, vb);
            }
            return 0;
        }

        private static int parseIntOr0(String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
        }

        /** 新增带 FeatureContext 的版本；旧的无参版本留给 library 规则。 */
        boolean matches(FeatureContext f) {
            String sysVer = System.getProperty("os.version", "");
            if (osName != null && !osName.equals(currentOs())) return false;
            if (osArch != null && !osArch.equals(System.getProperty("os.arch"))) return false;
            if (osVersion != null && !System.getProperty("os.version").startsWith(osVersion)) return false;
            if (osVersionMin != null && compareVersion(sysVer, osVersionMin) < 0) return false;
            if (osVersionMax != null && compareVersion(sysVer, osVersionMax) > 0) return false;

            if (demoUser && !f.demoUser()) return false;
            if (customResolution && !f.customResolution()) return false;
            if (quickPlaysSupport && !f.quickPlaysSupport()) return false;
            if (quickPlaySingleplayer && !f.quickPlaySingleplayer()) return false;
            if (quickPlayMultiplayer && !f.quickPlayMultiplayer()) return false;
            return !quickPlayRealms || f.quickPlayRealms();
        }

        static List<Rule> ofAll(JsonArray arr) {
            if (arr == null) return null;
            var out = new ArrayList<Rule>(arr.size());
            for (var el : arr) if (el instanceof JsonObject o) out.add(of(o));
            return out;
        }

        static Rule of(JsonObject o) {
            String action = str(o, "action");
            String osName = null, osArch = null, osVersion = null;
            String osVersionMin = null, osVersionMax = null;
            if (o.get("os") instanceof JsonObject os) {
                osName = str(os, "name");
                osArch = str(os, "arch");
                osVersion = str(os, "version");
                if (os.get("versionRange") instanceof JsonObject vr) {   // ← 解析 versionRange
                    osVersionMin = str(vr, "min");
                    osVersionMax = str(vr, "max");
                }
            }
            boolean demo = false, res = false, qps = false, qp1 = false, qp2 = false, qpr = false;
            if (o.get("features") instanceof JsonObject f) {
                demo = bool(f, "is_demo_user");
                res = bool(f, "has_custom_resolution");
                qps = bool(f, "has_quick_plays_support");
                qp1 = bool(f, "is_quick_play_singleplayer");
                qp2 = bool(f, "is_quick_play_multiplayer");
                qpr = bool(f, "is_quick_play_realms");
            }
            return new Rule(action, osName, osArch, osVersion, osVersionMin, osVersionMax, demo, res, qps, qp1, qp2, qpr);
        }

        public static boolean evaluate(List<Rule> rules) {
            return evaluate(rules, FeatureContext.NONE);
        }

        public static boolean evaluate(List<Rule> rules, FeatureContext f) {
            if (rules == null || rules.isEmpty()) return true;
            boolean allow = false;
            for (var r : rules) if (r.matches(f)) allow = "allow".equals(r.action);
            return allow;
        }

        public static String currentOs() {
            var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("win")) return "windows";
            if (os.contains("mac")) return "osx";
            if (os.contains("linux")) return "linux";
            return os;
        }
    }

    // =================== arguments ===================

    public record Arguments(List<Argument> game, List<Argument> jvm,
                            List<Argument> defaultUserJvm) {
        public Arguments {
            game = game == null ? List.of() : List.copyOf(game);
            jvm = jvm == null ? List.of() : List.copyOf(jvm);
            defaultUserJvm = defaultUserJvm == null ? List.of() : List.copyOf(defaultUserJvm);
        }

        static Arguments of(JsonObject o) {
            return o == null ? null
                    : new Arguments(readList(o.getAsJsonArray("game")),
                    readList(o.getAsJsonArray("jvm")),
                    readList(o.getAsJsonArray("default-user-jvm")));
        }

        private static List<Argument> readList(JsonArray arr) {
            if (arr == null) return List.of();
            var out = new ArrayList<Argument>(arr.size());
            for (var el : arr) {
                switch (el) {
                    case JsonPrimitive p -> out.add(new Argument.Plain(p.getAsString()));
                    case JsonObject o -> out.add(new Argument.Conditional(
                            valuesOf(o.get("value")),
                            Rule.ofAll(o.getAsJsonArray("rules"))));
                    default -> {
                    }
                }
            }
            return out;
        }

        private static List<String> valuesOf(JsonElement v) {
            return switch (v) {
                case JsonPrimitive p -> List.of(p.getAsString());
                case JsonArray a -> {
                    var list = new ArrayList<String>(a.size());
                    for (var x : a) list.add(x.getAsString());
                    yield List.copyOf(list);
                }
                case null, default -> List.of();
            };
        }
    }

    public sealed interface Argument permits Argument.Plain, Argument.Conditional {

        /** 无 Feature 上下文（FeatureContext.NONE）。 */
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean passes();

        record Plain(String value) implements Argument {
            @Override
            public boolean passes() {
                return true;
            }
        }

        record Conditional(List<String> values, List<Rule> rules) implements Argument {
            public Conditional {
                values = values == null ? List.of() : List.copyOf(values);
            }

            @Override
            public boolean passes() {
                return Rule.evaluate(rules, FeatureContext.NONE);
            }
        }
    }


    // =================== helpers ===================

    private static String str(JsonObject o, String k) {
        var e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static long lng(JsonObject o, String k) {
        var e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? 0L : e.getAsLong();
    }

    private static boolean bool(JsonObject o, String k) {
        var e = o == null ? null : o.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
}