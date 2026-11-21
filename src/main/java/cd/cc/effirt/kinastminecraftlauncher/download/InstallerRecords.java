package cd.cc.effirt.kinastminecraftlauncher.download;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 安装器 jar / version json 的原始记录读取工具（libraries / processors / data）。
 */
public class InstallerRecords {

    public static JsonObject readJson(Path file) throws IOException {
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed JSON: " + file, e);
        }
    }

    /** 读取 zip 内某个条目的文本；条目不存在返回 {@code null}。 */
    public static String readTextFromZip(Path zipFile, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** 读取 zip 内某个条目的 JSON；条目不存在返回 {@code null}。 */
    public static JsonObject readJsonFromZip(Path zipFile, String entryName) throws IOException {
        String text = readTextFromZip(zipFile, entryName);
        if (text == null) return null;
        try {
            JsonElement el = JsonParser.parseString(stripBom(text));
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new IOException("Malformed JSON in " + zipFile.getFileName() + "!" + entryName, e);
        }
    }

    /**
     * 读取安装器内嵌的版本 json。
     *
     * <p>三种形态：</p>
     * <ul>
     *   <li>现代安装器（1.13+）：jar 内 {@code version.json}（或 {@code __v.json}）；</li>
     *   <li>1.8.9 / 1.9.x / 1.10.x 的 SimpleInstaller：<b>没有</b> processors，
     *       版本 json 在 {@code install_profile.json} 的 <b>{@code versionInfo}</b> 字段；</li>
     *   <li>1.11+ 的旧式安装器：版本 json 在 {@code install_profile.json} 的 {@code install} 字段。</li>
     * </ul>
     *
     * @return 内嵌的版本 json；都没有时返回 {@code null}
     */
    public static JsonObject readInstallerVersionJson(Path installerJar, JsonObject installProfile)
            throws IOException {
        // 现代安装器：version json 在 jar 内 version.json / __v.json
        for (String name : new String[]{"version.json", "__v.json"}) {
            JsonObject json = readJsonFromZip(installerJar, name);
            if (json != null && json.has("mainClass")) return json;
        }
        if (installProfile != null) {
            // SimpleInstaller（1.8.9）：versionInfo
            if (installProfile.get("versionInfo") instanceof JsonObject versionInfo
                    && versionInfo.has("mainClass")) {
                return versionInfo;
            }
            // 1.11+ 旧式安装器：install 字段直接是版本 json
            if (installProfile.get("install") instanceof JsonObject legacy
                    && legacy.has("mainClass")) {
                return legacy;
            }
        }
        return null;
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }
}
