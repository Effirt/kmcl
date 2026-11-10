package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.kinastminecraftlauncher.MinecraftLoaderEnum;
import cd.cc.effirt.kinastminecraftlauncher.download.LoaderInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.exception.UnsupportedLoaderException;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
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
                InstanceCommand.Add.class,
                InstanceCommand.Rename.class,
                InstanceCommand.Del.class
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

    @Command(name = "add", description = "Add a new instance")
    static class Add implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<instanceName>", description = "Instance name")
        String name;

        @Option(names = {"-v", "--version"}, required = true, paramLabel = "<mcVersion>",
                description = "Minecraft version")
        String mcVersion;

        @Option(names = {"-l", "--loader"}, defaultValue = "vanilla", paramLabel = "<loader>",
                description = "Loader: vanilla / forge / fabric / neoforge")
        String loaderToken;

        @Option(names = {"-lv", "--loader-version"}, paramLabel = "<loaderVersion>",
                description = "Loader version (defaults to recommended/latest)")
        String loaderVersion;

        @Option(names = {"--retry"}, defaultValue = "5",
                description = "Retry attempts on download failure")
        int retry;

        @Override
        public Integer call() {
            MinecraftLoaderEnum loader;
            try {
                loader = MinecraftLoaderEnum.fromToken(loaderToken);
            } catch (IllegalArgumentException e) {
                Log.error(e.getMessage());
                return USAGE;
            }
            try {
                if (InstanceRegistry.get(name) != null) {
                    Log.error("Instance already exists: {}", name);
                    return FAILED;
                }

                Log.info("Adding instance '{}' (Minecraft {}, {})...",
                        name, mcVersion, loader.displayName());

                Path jsonFile = LoaderInstaller.install(loader, mcVersion, loaderVersion, name);

                if (LoaderInstaller.installsClientJar(loader)) {
                    VersionInstaller.installClient(name, jsonFile, retry);
                }
                VersionInstaller.installLibraries(name, jsonFile, retry);
                VersionInstaller.installAssets(jsonFile, retry);

                Log.info("Successfully added instance '{}' (Minecraft {})", name, mcVersion);
                return SUCCESSFUL;
            } catch (VersionNotFoundException | UnsupportedLoaderException e) {
                Log.error(e.getMessage());
                return USAGE;
            } catch (IOException e) {
                Log.error("Failed to add '{}'", name, e);
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

    @Command(name = "del", description = "Delete an instance")
    static class Del implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<instanceName>", description = "Instance name")
        String name;

        @Option(names = {"--data", "-d"},
                description = "Delete instances data")
        boolean data;

        @Override
        public Integer call() {
            try {
                InstanceManager.delete(name, data);
                return SUCCESSFUL;
            } catch (IOException e) {
                Log.error("Failed to delete: {}", e.getMessage(), e);
                return FAILED;
            }
        }
    }
}