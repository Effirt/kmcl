package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.auth.Account;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountStorage;
import cd.cc.effirt.kinastminecraftlauncher.launch.*;
import cd.cc.effirt.kinastminecraftlauncher.minecraft.InstanceList;
import cd.cc.effirt.util.Log;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "instance", aliases = {"inst"}, description = "Minecraft instance management",
        subcommands = {
                InstanceCommand.List.class,
                InstanceCommand.Start.class
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

    @Command(name = "list", description = "List installed Minecraft version")
    static class List implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @Override
        public Integer call() {
            InstanceList.list();
            return SUCCESSFUL;
        }
    }

    @Command(name = "start", description = "Launch an installed Minecraft instance")
    static class Start implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<name>", description = "Instance name")
        String name;


        @Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @Override
        public Integer call() {
            try {
                Account acc = getAccount();

                Log.info("Use account: {}", acc);
                int code = LaunchInstance.launch(name, acc);
                Log.info("Minecraft exit code: {}", code);
                return code == 0 ? SUCCESSFUL : FAILED;
            } catch (IllegalArgumentException e) {
                Log.error(e.getMessage());
                return FAILED;
            } catch (IOException e) {
                Log.error("Failed to launch '{}'", name, e.getMessage());
                return FAILED;
            }
        }

        private Account getAccount() throws IOException {
            AccountStorage storage = new AccountStorage(KMCLPath.getKmclPath().resolve("accounts"));

            String targetName = storage.getCurrent()
                    .orElseThrow(() -> new IllegalArgumentException("No account specified"));

            return storage.load(targetName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown account: " + targetName));
        }
    }
}