package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.kinastminecraftlauncher.MinecraftLoaderEnum;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.fabric.FabricInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.neoforge.NeoForgeInstaller;
import cd.cc.effirt.kinastminecraftlauncher.exception.UnsupportedLoaderException;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceInfo;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceList;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceManager;
import cd.cc.effirt.kinastminecraftlauncher.instance.InstanceRegistry;
import cd.cc.effirt.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "instance", aliases = {"inst"}, description = "Minecraft instance management",
        subcommands = {
                InstanceCommand.List.class,
                InstanceCommand.Create.class,
                InstanceCommand.Rename.class,
                InstanceCommand.Delete.class
        }
)
public class InstanceCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return USAGE;
    }

    // ==================== list ====================

    @Command(name = "list", description = "List installed instances")
    static class List implements Callable<Integer> {
        @Override
        public Integer call() {
            InstanceList.list();
            return SUCCESSFUL;
        }
    }

    // ==================== create ====================

    @Command(name = "create", description = "Create a new instance")
    static class Create implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<instanceName>", description = "Instance name")
        String name;

        @Option(names = {"-v", "--version"}, required = true, paramLabel = "<mcVersion>",
                description = "Minecraft version")
        String mcVersion;

        @Option(names = {"-l", "--loader"}, defaultValue = "vanilla",
                description = "Loader (vanilla/fabric/neoforge)")
        MinecraftLoaderEnum loader;

        @Option(names = {"-lv", "--loader-version"},
                description = "Loader version (defaults to latest stable)")
        String loaderVersion;

        @Option(names = {"--retry"}, defaultValue = "5",
                description = "Retry attempts on download failure")
        int retry;

        @Override
        public Integer call() {
            try {
                if (InstanceRegistry.get(name) != null) {
                    Log.error("Instance already exists: {}", name);
                    return FAILED;
                }

                Log.info("Creating instance '{}' (Minecraft {}, {})...",
                        name, mcVersion, loader);

                Path jsonFile = switch (loader) {
                    case vanilla -> {
                        Path p = VersionDownloader.downloadVanillaJson(mcVersion, name);
                        InstanceRegistry.put(name, new InstanceInfo(mcVersion, "vanilla", null));
                        yield p;
                    }
                    case fabric -> FabricInstaller.install(mcVersion, loaderVersion, name);
                    case neoforge -> NeoForgeInstaller.install(mcVersion, loaderVersion, name);
                    default -> throw new UnsupportedLoaderException(loader);
                };

                if (loader != MinecraftLoaderEnum.neoforge) {
                    VersionInstaller.installClient(name, jsonFile, retry);
                }
                VersionInstaller.installLibraries(name, jsonFile, retry);
                VersionInstaller.installAssets(jsonFile, retry);

                Log.info("Successfully created instance '{}' (Minecraft {})", name, mcVersion);
                return SUCCESSFUL;
            } catch (VersionNotFoundException | UnsupportedLoaderException e) {
                Log.error(e.getMessage());
                return USAGE;
            } catch (IOException e) {
                Log.error("Failed to create '{}'", name, e);
                return FAILED;
            }
        }
    }

    // ==================== rename ====================

    @Command(name = "rename", description = "Rename an instance")
    static class Rename implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<instanceName>", description = "Current name")
        String oldName;

        @Parameters(index = "1", paramLabel = "<newInstanceName>", description = "New name")
        String newName;

        @Override
        public Integer call() {
            try {
                InstanceManager.rename(oldName, newName);
                return SUCCESSFUL;
            } catch (IOException e) {
                Log.error("Failed to rename: {}", e.getMessage());
                return FAILED;
            }
        }
    }

    // ==================== delete ====================

    @Command(name = "delete", description = "Delete an instance")
    static class Delete implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<instanceName>", description = "Instance name")
        String name;

        @Option(names = {"--keep-data"},
                description = "Keep instances/<name>/ (saves, configs, mods)")
        boolean keepData;

        @Override
        public Integer call() {
            try {
                InstanceManager.delete(name, keepData);
                return SUCCESSFUL;
            } catch (IOException e) {
                Log.error("Failed to delete: {}", e.getMessage());
                return FAILED;
            }
        }
    }
}