package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.kinastminecraftlauncher.download.fabric.FabricInstaller;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionDownloader;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionManifest;
import cd.cc.effirt.kinastminecraftlauncher.exception.UnsupportedLoaderException;
import cd.cc.effirt.kinastminecraftlauncher.exception.VersionNotFoundException;
import cd.cc.effirt.kinastminecraftlauncher.MinecraftLoaderEnum;
import cd.cc.effirt.kinastminecraftlauncher.minecraft.MinecraftVersionList;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "download", aliases = {"down"}, description = "Download Minecraft",
        subcommands = {
                DownloadCommand.List.class,
                DownloadCommand.Version.class
        }
)
public class DownloadCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return USAGE;
    }

    @Command(name = "list", description = "View Minecraft version list")
    static class List implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @ArgGroup()
        Mode mode;

        public static class Mode {
            @Option(names = {"-a", "--all"},
                    description = "Show all versions")
            public boolean all;

            @Option(names = {"-l", "--latest"},
                    description = "Show only the latest matching version")
            public boolean latest;
        }

        public static class Filter {
            @Option(names = {"-r", "--release"}, description = "Show only release versions")
            public boolean release;

            @Option(names = {"-s", "--snapshot"}, description = "Show only snapshot versions")
            public boolean snapshot;
        }

        @ArgGroup()
        Filter filter;

        @Override
        public Integer call() {
            boolean all = mode != null && mode.all;
            boolean latest = mode != null && mode.latest;
            boolean release = filter != null && filter.release;
            boolean snapshot = filter != null && filter.snapshot;

            if (latest) {
                if (release) MinecraftVersionList.printSingle(VersionManifest.getRelease());
                else if (snapshot) MinecraftVersionList.printSingle(VersionManifest.getSnapshot());
                else MinecraftVersionList.printSingle(VersionManifest.getLatest());
            } else if (release) {
                MinecraftVersionList.printRelease();
            } else if (snapshot) {
                MinecraftVersionList.printSnapshot();
            } else if (all) {
                MinecraftVersionList.printAll();
            } else {
                spec.commandLine().usage(System.err);
                return USAGE;
            }
            return SUCCESSFUL;
        }
    }

    @Command(name = "version", description = "Download Minecraft version")
    static class Version implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<minecraftVersion>",
                description = "Minecraft version id")
        String mcVersion;

        @Option(names = {"-n", "--name"}, required = true,
                description = "Specify the name for the downloaded version")
        String name;

        @Option(names = {"-l", "--loader"},
                description = "Specify the loader", defaultValue = "vanilla")
        MinecraftLoaderEnum loader;

        @Option(names = {"--retry"}, defaultValue = "5",
                description = "Retry attempts on download failure")
        int retry;

        @Option(names = {"--loader-version"},
                description = "Loader version")
        String loaderVersion;

        @Override
        public Integer call() {
            try {
                Log.info("Installing {} version...", mcVersion);

                Path jsonFile = switch (loader) {
                    case vanilla -> VersionDownloader.downloadVanillaJson(mcVersion, name);
                    case fabric -> FabricInstaller.install(mcVersion, loaderVersion, name);
                    default -> throw new UnsupportedLoaderException(loader);
                };

                VersionInstaller.installClient(jsonFile, name, retry);
                VersionInstaller.installLibraries(jsonFile, name, retry);
                VersionInstaller.installAssets(jsonFile, retry);
                Log.info("Successful install instance '{}' (Minecraft {})", name, mcVersion);
                return SUCCESSFUL;
            } catch (VersionNotFoundException | UnsupportedLoaderException e) {
                Log.error(e.getMessage());
                return USAGE;
            } catch (IOException e) {
                Log.error("Failed to install '{}' version", mcVersion, e);
                return FAILED;
            }
        }
    }
}