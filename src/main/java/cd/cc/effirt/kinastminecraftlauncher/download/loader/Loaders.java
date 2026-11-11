package cd.cc.effirt.kinastminecraftlauncher.download.loader;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionJSONParser;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceInfoRecord;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceRegistry;
import cd.cc.effirt.auroralib.Log;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forge / NeoForge / Fabric 安装器共用的落盘与注册逻辑。
 *
 * <p>下载与执行细节各自放在 {@code download.forge} / {@code download.neoforge} /
 * {@code download.fabric} 包里；这里只负责“把 loader 提供的 version json
 * 映射成实例目录下的 {@code <mcVersion>.json} 并登记到 instances.json”。</p>
 */
public class Loaders {

    /**
     * 把加载器为原版补丁出的 version json 写进实例目录。
     *
     * <p>写入前先尝试把 {@code inheritsFrom} 链折叠成扁平 json：<b>但不会折叠</b>那些
     * 单独带 {@code jar} 字段（即内嵌客户端 jar）的子版本，因为合并会把该覆盖信息带丢。</p>
     */
    public static Path writeInstanceJson(String instanceName, String mcVersion,
                                         JsonObject loaderJson, MinecraftVersionSource source)
            throws IOException {
        Path target = KMCLPath.getKmclPath().resolve("versions").resolve(instanceName)
                .resolve(mcVersion + ".json");
        Files.createDirectories(target.getParent());

        JsonObject toWrite = loaderJson;
        try {
            if (hasInherits(loaderJson)) {
                toWrite = VersionJSONParser.resolve(loaderJson, source);
            }
        } catch (IOException e) {
            Log.warn("Could not flatten inheritsFrom for '{}' ({}); writing as-is",
                    instanceName, e.getMessage());
            toWrite = loaderJson.deepCopy();
        }
        if (!toWrite.has("id")) toWrite.addProperty("id", instanceName);
        Files.writeString(target, toWrite.toString(), StandardCharsets.UTF_8);

        Log.info("Wrote instance version json: {}", target);
        return target;
    }

    /** 供继承链解析器使用的版本来源。 */
    @FunctionalInterface
    public interface MinecraftVersionSource extends VersionJSONParser.VersionProvider {
    }

    /** 默认来源：Mojang version_manifest。 */
    public static MinecraftVersionSource mojang() {
        return VersionDownloader::fetchVanillaJson;
    }

    public static void register(String instanceName, String mcVersion, String loader,
                                String loaderVersion) throws IOException {
        InstanceRegistry.put(instanceName, new InstanceInfoRecord(mcVersion, loader, loaderVersion));
    }

    /** 判断 json 是否还依赖父版本（{@code inheritsFrom} 或简化写法 {@code librariesFrom}）。 */
    public static boolean hasInherits(JsonObject json) {
        JsonElement inherits = json.get("inheritsFrom");
        if (inherits != null && !inherits.isJsonNull() && !inherits.getAsString().isBlank()) {
            return true;
        }
        return json.get("librariesFrom") instanceof JsonObject;
    }
}
