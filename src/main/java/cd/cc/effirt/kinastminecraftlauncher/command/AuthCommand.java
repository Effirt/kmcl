package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.kinastminecraftlauncher.auth.Account;
import cd.cc.effirt.kinastminecraftlauncher.auth.AccountStorage;
import cd.cc.effirt.kinastminecraftlauncher.auth.MicrosoftAuthManager;
import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.util.Table;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "auth", description = "Auth management",
        subcommands = {
                AuthCommand.Add.class,
                AuthCommand.Delete.class,
                AuthCommand.List.class,
                AuthCommand.Use.class
        })
public class AuthCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return USAGE;
    }

    @Command(name = "add", description = "Add an account (offline or Microsoft)")
    static class Add implements Callable<Integer> {
        @ArgGroup(multiplicity = "1")
        Target target;

        static class Target {
            @Option(names = "-m", description = "Microsoft account (device code flow)")
            boolean microsoft;

            @Parameters(index = "0", description = "Offline player name")
            String player;
        }

        @Override
        public Integer call() {
            try {
                AccountStorage storage = new AccountStorage(KMCLPath.getKmclPath().resolve("accounts"));

                Account acc = target.microsoft
                        ? MicrosoftAuthManager.login()
                        : Account.offline(target.player);
                Log.info("Adding {} account: {} ({})", acc.type(), acc.name(), acc.uuid());
                storage.save(acc);
                return SUCCESSFUL;
            } catch (Exception e) {
                Log.error("Failed to add account", e);
                return FAILED;
            }
        }
    }

    @Command(name = "delete", description = "Delete an account")
    static class Delete implements Callable<Integer> {
        @Parameters(index = "0", description = "Player name")
        String player;

        @Override
        public Integer call() {
            try {
                AccountStorage storage = new AccountStorage(KMCLPath.getKmclPath().resolve("accounts"));
                boolean wasCurrent = storage.getCurrent()
                        .map(n -> n.equals(player)).orElse(false);

                if (!storage.delete(player)) {
                    Log.error("No such account: {}", player);
                    return FAILED;
                }
                if (wasCurrent) {
                    storage.clearCurrent();
                    Log.info("Deleted account and cleared default: {}", player);
                } else {
                    Log.info("Deleted account: {}", player);
                }
                return SUCCESSFUL;
            } catch (Exception e) {
                Log.error("Failed to delete account", e);
                return FAILED;
            }
        }
    }

    @Command(name = "list", description = "List accounts")
    static class List implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                AccountStorage storage = new AccountStorage(KMCLPath.getKmclPath().resolve("accounts"));
                var accounts = storage.list();
                if (accounts.isEmpty()) {
                    Log.info("No account");
                    return SUCCESSFUL;
                }
                var current = storage.getCurrent();
                Table table = new Table("#", "Name", "Type", "UUID");
                for (Account a : accounts) {
                    String marker = current.filter(n -> n.equals(a.name())).isPresent() ? "*" : ".";
                    table.row(marker, a.name(), a.type().toString(), a.uuid().toString());
                }
                IO.println(table.render());
                return SUCCESSFUL;
            } catch (Exception e) {
                Log.error("Failed to list accounts", e);
                return FAILED;
            }
        }
    }

    @Command(name = "use", description = "Set or show the default account")
    static class Use implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1",
                description = "Account name (omit to show current)")
        String player;

        @Override
        public Integer call() {
            try {
                AccountStorage storage = new AccountStorage(KMCLPath.getKmclPath().resolve("accounts"));
                if (player == null) {
                    storage.getCurrent().ifPresentOrElse(
                            name -> Log.info("Current account: {}", name),
                            () -> Log.info("No account in use")
                    );
                    return SUCCESSFUL;
                }
                storage.setCurrent(player);
                Log.info("Now using account: {}", player);
                return SUCCESSFUL;
            } catch (Exception e) {
                Log.error("Failed to set current account", e);
                return FAILED;
            }
        }
    }
}