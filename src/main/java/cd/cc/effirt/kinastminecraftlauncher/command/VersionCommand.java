package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.kinastminecraftlauncher.minecraft.MinecraftVersionList;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.SUCCESSFUL;
import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.USAGE;

@Command(name = "version", aliases = {"ver"}, description = "View Minecraft version list")
public class VersionCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @ArgGroup()
    Mode mode;

    static class Mode {
        @Option(names = {"-a", "--all"}, description = "Show all matching versions")
        boolean all;

        @Option(names = {"-l", "--latest"}, description = "Show only the latest matching version")
        boolean latest;
    }

    @ArgGroup()
    Filter filter;

    static class Filter {
        @Option(names = {"-r", "--release"}, description = "Release versions only")
        boolean release;

        @Option(names = {"-s", "--snapshot"}, description = "Snapshot versions only")
        boolean snapshot;

        @Option(names = {"-o", "--old"}, description = "Old alpha / beta versions")
        boolean old;
    }

    @Override
    public Integer call() {
        boolean all = mode != null && mode.all;
        boolean latest = mode != null && mode.latest;
        boolean release = filter != null && filter.release;
        boolean snapshot = filter != null && filter.snapshot;
        boolean old = filter != null && filter.old;

        // 无任何参数 → 显示 help
        if (!all && !latest && !release && !snapshot && !old) {
            spec.commandLine().usage(System.err);
            return USAGE;
        }

        Set<String> types = new LinkedHashSet<>();
        if (release) types.add("release");
        if (snapshot) types.add("snapshot");
        if (old) {
            types.add("old_alpha");
            types.add("old_beta");
        }
        if (types.isEmpty()) types.add("release");   // 无 filter → release


        if (latest) {
            MinecraftVersionList.printLatestByTypes(types);
        } else {
            MinecraftVersionList.printByTypes(types);
        }
        return SUCCESSFUL;
    }
}