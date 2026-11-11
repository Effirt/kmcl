package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.auroralib.Log;
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
        String fn = file.getFileName().toString();
        String mcVersion = fn.endsWith(".json")
                ? fn.substring(0, fn.length() - ".json".length())
                : null;
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r).getAsJsonObject(), mcVersion);
        } catch (JsonParseException e) {
            // JsonSyntaxException 继承自 JsonParseException：一并转成 IOException，
            // 否则 picocli 不会捕获，启动/安装会直接打出堆栈
            Log.error("Malformed version JSON: {}", file, e);
            throw new IOException("Malformed version JSON: " + file, e);
        }
    }

    public static VersionInfo parse(JsonObject root, String mcVersion) {
        var loggingRoot = root.getAsJsonObject("logging");
        var loggingClient = loggingRoot == null ? null
                : Logging.of(loggingRoot.getAsJsonObject("client"));
        boolean inherited = hasInheritsFrom(root);
        VersionInfo own = new VersionInfo(
                str(root, "id"),
                str(root, "type"),
                str(root, "mainClass"),
                str(root, "assets"),
                str(root, "minecraftArguments"),
                mcVersion != null ? mcVersion : resolveMcVersion(root),
                AssetIndex.of(root.getAsJsonObject("assetIndex")),
                Downloads.of(root.getAsJsonObject("downloads")),
                JavaVersion.of(root.getAsJsonObject("javaVersion")),
                Library.ofAll(root.getAsJsonArray("libraries")),
                Arguments.of(root.getAsJsonObject("arguments")),
                loggingClient
        );
        String childJar = str(root, "jar");
        return new VersionInfo(own.id(), own.type(), own.mainClass(), own.assets(),
                own.minecraftArguments(), own.mcVersion(), own.assetIndex(), own.downloads(),
                own.javaVersion(), own.libraries(), own.arguments(), own.loggingClient(),
                str(root, "inheritsFrom"),
                childJar == null || childJar.isBlank() ? null : childJar,
                inherited);
    }

    private static boolean hasInheritsFrom(JsonObject root) {
        var inherits = root.get("inheritsFrom");
        return inherits != null && !inherits.isJsonNull() && !inherits.getAsString().isBlank();
    }

    private static String resolveMcVersion(JsonObject root) {
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
            Logging loggingClient,
            String inheritsFrom,          // Forge/NeoForge 1.13+ 的父版本 id；无则 null
            String jar,                   // 可选：客户端 jar 覆盖（Forge 用 net.minecraft:client:...）
            boolean inherited             // true 表示该 json 依赖目标版本（未合并）
    ) {
        public VersionInfo {
            libraries = libraries == null ? List.of() : List.copyOf(libraries);
        }

        /** 兼容旧签名的构造：不含继承信息。 */
        public VersionInfo(String id, String type, String mainClass, String assets,
                           String minecraftArguments, String mcVersion, AssetIndex assetIndex,
                           Downloads downloads, JavaVersion javaVersion, List<Library> libraries,
                           Arguments arguments, Logging loggingClient) {
            this(id, type, mainClass, assets, minecraftArguments, mcVersion, assetIndex, downloads,
                    javaVersion, libraries, arguments, loggingClient, null, null, false);
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
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** 新增带 FeatureContext 的版本；旧的无参版本留给 library 规则。 */
        boolean matches(FeatureContext f) {
            String sysVer = System.getProperty("os.version", "");
            if (osName != null && !osName.equals(currentOs())) return false;
            if (osArch != null && !osArch.equals(System.getProperty("os.arch"))) return false;
            if (osVersion != null && !sysVer.startsWith(osVersion)) return false;
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
            if (o == null) return null;
            // 不用 o.getAsJsonArray(...)：gson 会在嵌套对象上抛 ClassCastException
            return new Arguments(readList(arrayOf(o, "game")),
                    readList(arrayOf(o, "jvm")),
                    readList(arrayOf(o, "default-user-jvm")));
        }

        private static JsonArray arrayOf(JsonObject o, String key) {
            return o.get(key) instanceof JsonArray arr ? arr : null;
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

    // =================== 继承链合并 ===================

    /**
     * 解析 {@code inheritsFrom} 链（例如 实例 → Forge → 原版）。
     *
     * <p>每一环都按 {@link #genericMerge} 折叠：子版本覆盖所有单值字段，
     * {@code libraries} 按 Maven 坐标覆盖、{@code arguments} 追加，
     * 原版的 {@code downloads} 等只由父版本提供。子版本允许使用简化写法
     * {@code "librariesFrom": { "parent": [ ... ] }} 追加一组库，不需要父版本的 id
     * 就能追踪继承关系。</p>
     *
     * <p>合并结果里 {@code inheritsFrom} 会被移除，因此可以直接由
     * {@link VersionJSONParser#parse(JsonObject, String)} 解析成扁平信息。</p>
     */
    public static JsonObject resolve(JsonObject version, VersionProvider provider) throws IOException {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(provider, "provider");
        Deque<JsonObject> chain = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        JsonObject current = version;
        while (current != null) {
            String id = str(current, "id");
            String parentId = str(current, "inheritsFrom");
            chain.addLast(current);
            if (parentId == null || parentId.isBlank()) break;
            if (id == null) {
                id = parentId;          // 无 id 的子版本用父版本 id 作为链标识
            }
            if (!visited.add(id)) {
                throw new IOException("Circular inheritsFrom chain at '" + id + "'");
            }
            current = provider.load(parentId);
        }

        JsonObject merged = chain.removeLast();
        while (!chain.isEmpty()) {
            merged = genericMerge(chain.removeLast(), merged);
        }
        return merged;
    }

    /** {@code inheritsFrom} 目标版本的获取口（通常是 Mojang 的 version_manifest）。 */
    @FunctionalInterface
    public interface VersionProvider {
        /** @return 该版本 id 的<b>原始</b> json（可以仍带 inheritsFrom） */
        JsonObject load(String versionId) throws IOException;
    }

    /**
     * 把 {@code child} 叠加到 {@code parent} 上（Minecraft 继承语义）。
     *
     * <ul>
     *   <li>{@code downloads / assetIndex / assets / mainClass / type / logging / javaVersion /
     *       complianceLevel / id ...} 等标量字段由子版本覆盖父版本；</li>
     *   <li>{@code libraries} 先子后父，按 Maven 坐标去重（子版本优先）；</li>
     *   <li>{@code arguments.game / jvm / default-user-jvm} 先子后父追加；</li>
     *   <li>{@code minecraftArguments} 与 {@code arguments} 同时存在时只保留 {@code arguments}；</li>
     *   <li>{@code inheritsFrom} 与简化写法 {@code librariesFrom} 不会进入结果。</li>
     * </ul>
     *
     * <p>刻意不使用 {@code JsonElement#deepCopy()}：它会把内部容器标记为 deepCopy，
     * 之后再调用 {@code getAsJsonArray(...)} 会抛 ClassCastException，并污染父对象。</p>
     */
    public static JsonObject genericMerge(JsonObject child, JsonObject parent) {
        Objects.requireNonNull(child, "child");
        JsonObject out = new JsonObject();
        for (String k : child.keySet()) {
            if (isDynamicKey(k)) continue;
            out.add(k, child.get(k));
        }
        if (parent != null) {
            for (String k : parent.keySet()) {
                if (isDynamicKey(k) || out.has(k)) continue;
                out.add(k, parent.get(k));
            }
        }
        out.remove("inheritsFrom");
        out.remove("librariesFrom");
        // 子版本优先：只有子版本没有的字段才由父版本补齐
        for (String key : new String[]{"arguments", "libraries"}) {
            JsonElement childEl = child.get(key);
            JsonElement parentEl = parent == null ? null : parent.get(key);
            JsonElement merged = mergeField(key, childEl, parentEl);
            if (merged != null) out.add(key, merged);
        }
        if (out.has("arguments")) out.remove("minecraftArguments");
        return out;
    }

    /**
     * 合并 {@code arguments} / {@code libraries}：子版本条目在前（同名条目优先取子版本），
     * 父版本中未被子版本覆盖的条目按原顺序追加在后。
     *
     * @return 合并结果；无内容返回 {@code null}
     */
    private static JsonElement mergeField(String key, JsonElement childEl, JsonElement parentEl) {
        JsonArray childArr = childEl instanceof JsonArray ca ? ca : null;
        JsonArray parentArr = parentEl instanceof JsonArray pa ? pa : null;
        boolean arguments = key.equals("arguments");

        JsonObject mergedArgs = arguments ? new JsonObject() : null;
        JsonArray mergedLibs = arguments ? null : appendChildFirst(childArr, parentArr);

        if (arguments) {
            for (String g : new String[]{"game", "jvm", "default-user-jvm"}) {
                // 分组经 asMap() 安全取值：gson 的 getAsJsonArray 在嵌套场景会误抛 ClassCastException
                JsonArray childGroup = groupArray(childEl, g);
                JsonArray parentGroup = groupArray(parentEl, g);
                JsonArray m = appendChildFirst(childGroup, parentGroup);
                if (!m.isEmpty()) {
                    mergedArgs.add(g, m);
                } else {
                    // 子版本干脆没写 arguments 时，原样沿用父版本的那一组
                    copyGroupIfPresent(mergedArgs, parentEl, g);
                }
            }
        }
        return finishMerge(mergedArgs, mergedLibs);
    }

    /** 合并结果为空时返回 null。 */
    private static JsonElement finishMerge(JsonObject args, JsonArray libs) {
        if (args != null && args.isEmpty()) {
            if (libs != null && !libs.isEmpty()) return libs;
            return null;
        }
        if (args != null) return args;
        if (libs != null && !libs.isEmpty()) return libs;
        return null;
    }

    /** 父版本的某组参数原样搬过来（只在子版本完全没写 arguments 时使用）。 */
    private static void copyGroupIfPresent(JsonObject dst, JsonElement src, String g) {
        if (src == null || !src.isJsonObject()) return;
        JsonElement inner = src.getAsJsonObject().get(g);
        if (inner instanceof JsonArray arr && !arr.isEmpty()) dst.add(g, arr);
    }

    private static JsonArray groupArray(JsonElement el, String g) {
        if (el == null || el.isJsonNull() || !el.isJsonObject()) return null;
        java.util.Map<String, com.google.gson.JsonElement> map =
                new java.util.HashMap<>(el.getAsJsonObject().asMap());
        JsonElement inner = map.get(g);
        return inner != null && inner.isJsonArray() ? inner.getAsJsonArray() : null;
    }

    /** 子版本条目在前；父版本里与子版本同名的条目被跳过。 */
    private static JsonArray appendChildFirst(JsonArray childArr, JsonArray parentArr) {
        JsonArray out = new JsonArray();
        Set<String> seen = new HashSet<>();
        if (childArr != null) {
            for (JsonElement el : childArr) {
                if (el == null || el.isJsonNull()) continue;
                String key = dedupeKey(el);
                if (key != null) {
                    seen.add(key);
                }
                out.add(el);
            }
        }
        if (parentArr != null) {
            for (JsonElement el : parentArr) {
                if (el == null || el.isJsonNull()) continue;
                String key = dedupeKey(el);
                if (key != null && !seen.add(key)) continue;
                out.add(el);
            }
        }
        return out;
    }

    /** libraries 用 Maven 坐标去重；参数条目只对纯字符串去重。 */
    private static String dedupeKey(JsonElement el) {
        if (!(el instanceof JsonObject o)) return null;
        return str(o, "name") == null ? null : str(o, "name");
    }

    private static boolean isDynamicKey(String k) {
        return k.equals("libraries") || k.equals("arguments") || k.equals("minecraftArguments")
                || k.equals("inheritsFrom") || k.equals("librariesFrom");
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