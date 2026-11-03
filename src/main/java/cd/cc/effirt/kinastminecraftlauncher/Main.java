package cd.cc.effirt.kinastminecraftlauncher;

import cd.cc.effirt.kinastminecraftlauncher.auth.Account;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountStorage;
import cd.cc.effirt.kinastminecraftlauncher.command.AuthCommand;
import cd.cc.effirt.kinastminecraftlauncher.command.InstanceCommand;
import cd.cc.effirt.kinastminecraftlauncher.command.VersionCommand;
import cd.cc.effirt.kinastminecraftlauncher.instance.launch.LaunchInstance;
import cd.cc.effirt.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "kmcl",
        mixinStandardHelpOptions = true,
        subcommands = {
                InstanceCommand.class,
                AuthCommand.class,
                VersionCommand.class
        },
        versionProvider = VersionProvider.class
)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<instanceName>",
            description = "Instance name to launch")
    String instanceName;

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
        if (instanceName == null) {
            CommandLine.usage(this, System.err);
            return USAGE;
        }
        try {
            Account acc = AccountStorage.getAccount();
            Log.info("Use account: {}", acc);
            int code = LaunchInstance.launch(instanceName, acc);
            Log.info("Minecraft exit code: {}", code);
            return code == 0 ? SUCCESSFUL : FAILED;
        } catch (IllegalArgumentException e) {
            Log.error(e.getMessage());
            return FAILED;
        } catch (IOException e) {
            Log.error("Failed to launch '{}'", instanceName, e);
            return FAILED;
        }
    }
}