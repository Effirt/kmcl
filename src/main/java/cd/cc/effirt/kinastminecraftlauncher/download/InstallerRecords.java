package cd.cc.effirt.kinastminecraftlauncher.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    /** 读取 zip 内某个条目的 JSON；条目不存在返回 {@code null}。 */
    public static JsonObject readJsonFromZip(Path zipFile, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        } catch (RuntimeException e) {
            throw new IOException("Malformed JSON in " + zipFile.getFileName() + "!" + entryName, e);
        }
    }
}
