package cd.cc.effirt.kinastminecraftlauncher.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge / NeoForge 安装器里 {@code install_profile.json} 的 processors 摘要。
 *
 * <h2>用途</h2>
 * 现代 Forge 的 version json（{@code inheritsFrom} 父版本的那种）<b>不</b>包含完整依赖：
 * 真正需要的全部 libraries、processors 与 data 都在安装器的 {@code install_profile.json} 里。
 * 这里把它们读出来，安装结束后写入实例目录的 {@code <instance>.install.json}，
 * 并把 libraries 同步到 {@code instances.json}，供 launch / 校验 / 卸载使用。
 *
 * <h2>安全</h2>
 * 这里只读取 processors 的<b>数据</b>，绝不执行它们。processors 里承载着完整的
 * 反混淆与打补丁流程，实际执行交给 Forge 官方的 installer jar。
 */
public class ProcessorRecords {

    // ==================== records ====================

    public record InstallProfile(
            String spec,
            String version,
            String minecraft,
            String path,
            String filePath
    ) {
        public static InstallProfile EMPTY = new InstallProfile(null, null, null, null, null);

        public boolean isEmpty() {
            return spec == null && version == null && minecraft == null;
        }
    }

    /**
     * 一次 processor 执行。
     *
     * @param sides   {@code ["client"]} / {@code ["server"]} / 空表示双侧
     * @param args    processor 的原始参数（含 [{@code "literal"}] / [{@code "libraries"}] / [{@code "installer"}] 标记）
     * @param outputs processor 产出的中间文件
     */
    public record Processor(
            String jar,
            List<String> classpath,
            List<String> sides,
            List<JsonArray> args,
            List<String> outputs
    ) {
    }

    // ==================== 解析 ====================

    public static InstallProfile profile(JsonObject installProfile) {
        if (installProfile == null) return InstallProfile.EMPTY;
        return new InstallProfile(
                str(installProfile, "spec"),
                str(installProfile, "version"),
                str(installProfile, "minecraft"),
                str(installProfile, "path"),          // 1.12 及更早：path 形式的安装器
                str(installProfile, "filePath")
        );
    }

    public static List<Processor> processors(JsonObject installProfile) {
        List<Processor> out = new ArrayList<>();
        if (installProfile == null || !(installProfile.get("processors") instanceof JsonArray arr)) {
            return out;
        }
        for (JsonElement el : arr) {
            if (!(el instanceof JsonObject o)) continue;
            out.add(new Processor(
                    str(o, "jar"),
                    strings(o.get("classpath")),
                    strings(o.get("sides")),
                    rawArrays(o.get("args")),
                    strings(o.get("outputs"))
            ));
        }
        return out;
    }

    /** 数据块：{@code "data": { "KEY": { "client": "...", "server": "..." } }}。 */
    public static Map<String, Map<String, String>> data(JsonObject installProfile) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (installProfile == null || !(installProfile.get("data") instanceof JsonObject data)) {
            return out;
        }
        for (var e : data.entrySet()) {
            Map<String, String> sides = new LinkedHashMap<>();
            if (e.getValue() instanceof JsonObject sidesJson) {
                for (var s : sidesJson.entrySet()) {
                    sides.put(s.getKey(), plain(s.getValue()));
                }
            } else {
                sides.put("*", plain(e.getValue()));
            }
            out.put(e.getKey(), sides);
        }
        return out;
    }

    // ==================== helpers ====================

    private static String plain(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        // [{ "literal": "x" }] 之类的包装
        if (el.isJsonArray()) {
            for (JsonElement inner : el.getAsJsonArray()) {
                if (inner instanceof JsonObject o && o.has("literal")) {
                    return o.get("literal").getAsString();
                }
            }
        }
        return el.toString();
    }

    private static List<String> strings(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : el.getAsJsonArray()) {
            if (e != null && !e.isJsonNull()) out.add(e.getAsString());
        }
        return out;
    }

    private static List<JsonArray> rawArrays(JsonElement el) {
        List<JsonArray> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : el.getAsJsonArray()) {
            if (e instanceof JsonArray a) out.add(a);
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

}
