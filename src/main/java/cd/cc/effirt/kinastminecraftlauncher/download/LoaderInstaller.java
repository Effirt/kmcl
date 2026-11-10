package cd.cc.effirt.kinastminecraftlauncher.download;

import cd.cc.effirt.kinastminecraftlauncher.MinecraftLoaderEnum;
import cd.cc.effirt.kinastminecraftlauncher.download.fabric.FabricInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.forge.ForgeInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.neoforge.NeoForgeInstaller;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 加载器安装的统一入口：下载并组装“实例对应的 version json”。
 *
 * <p>返回的 json 已经是实例目录下的 {@code <mcVersion>.json}；后续由
 * {@link VersionInstaller} 统一下载客户端 / 库 / 资源。</p>
 */
public class LoaderInstaller {

    /**
     * 安装加载器并返回实例 version json 路径。
     *
     * @param loader        加载器（vanilla 由调用方直接走 {@link VersionDownloader}）
     * @param mcVersion     Minecraft 版本
     * @param loaderVersion 加载器版本；null/空表示默认（推荐/Latest）
     * @param instanceName  实例名
     */
    public static Path install(MinecraftLoaderEnum loader, String mcVersion,
                               String loaderVersion, String instanceName) throws IOException {
        return switch (loader) {
            case vanilla -> {
                Path p = VersionDownloader.downloadVanillaJson(mcVersion, instanceName);
                cd.cc.effirt.kinastminecraftlauncher.instance.InstanceRegistry.put(instanceName,
                        new cd.cc.effirt.kinastminecraftlauncher.instance.InstanceInfoRecord(
                                mcVersion, "vanilla", null));
                yield p;
            }
            case fabric -> FabricInstaller.install(mcVersion, loaderVersion, instanceName);
            case forge -> ForgeInstaller.install(mcVersion, loaderVersion, instanceName);
            case neoforge -> NeoForgeInstaller.install(mcVersion, loaderVersion, instanceName);
        };
    }

    /**
     * 安装后需要统一下载客户端 / 库 / 资源。
     *
     * @return 是否由 {@link VersionInstaller} 负责下载 client jar
     */
    public static boolean installsClientJar(MinecraftLoaderEnum loader) {
        return switch (loader) {
            // NeoForge 的 client 类来自 libraries（net.minecraft:client:*），实例目录不放 jar
            case neoforge -> false;
            default -> true;
        };
    }
}
