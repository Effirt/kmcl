
package cd.cc.effirt.kinastminecraftlauncher;

import cd.cc.effirt.kinastminecraftlauncher.command.AuthCommand;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.command.InstanceCommand;
import cd.cc.effirt.kinastminecraftlauncher.command.DownloadCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "KinastMinecraftLauncher",
        mixinStandardHelpOptions = true,
        subcommands = {
                DownloadCommand.class,
                InstanceCommand.class,
                AuthCommand.class
        },
        versionProvider = VersionProvider.class
)
public class Main implements Callable<Integer> {
    static void main(String[] args) {
        CommandLine cl = new CommandLine(new Main());

        cl.setParameterExceptionHandler((e, _) -> {
            Log.error(e.getMessage());
            e.getCommandLine().usage(System.err);

            return USAGE;
        });

        System.exit(cl.execute(args));
    }

    @Override
    public Integer call() {
        Log.error("TUI is WIP, please waiting...");
        CommandLine.usage(this, System.err);
        return USAGE;
    }
}
