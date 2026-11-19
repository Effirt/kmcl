package cd.cc.effirt.kinastminecraftlauncher.command;

import cd.cc.effirt.auroralib.Log;
import cd.cc.effirt.kinastminecraftlauncher.note.NoteRecord;
import cd.cc.effirt.kinastminecraftlauncher.note.NoteStorage;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

import static cd.cc.effirt.kinastminecraftlauncher.ExitCode.*;

@Command(name = "note", description = "Note management",
        subcommands = {
                NoteCommand.List.class,
                NoteCommand.Add.class,
                NoteCommand.Show.class,
                NoteCommand.Del.class
        })
public class NoteCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return USAGE;
    }

    // ==================== list ====================

    @Command(name = "list", description = "List all notes")
    static class List implements Callable<Integer> {
        @Override
        public Integer call() {
            NoteStorage.list();
            return SUCCESSFUL;
        }
    }

    // ==================== add ====================

    @Command(name = "add", description = "Add a note")
    static class Add implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<Title>", description = "Note title")
        String title;

        @Parameters(index = "1", arity = "1..*", paramLabel = "<context>",
                description = "Note content")
        String[] context;

        @Option(names = {"-f", "--force"}, description = "Overwrite if the note already exists")
        boolean force;

        @Override
        public Integer call() {
            String content = String.join(" ", context);
            try {
                if (!force && NoteStorage.exists(title)) {
                    Log.error("Note already exists: {} (use --force to overwrite)", title);
                    return FAILED;
                }
                NoteStorage.write(title, content);
                Log.info("Saved note '{}'", title.trim());
                return SUCCESSFUL;
            } catch (IllegalArgumentException e) {
                Log.error(e.getMessage());
                return USAGE;
            } catch (IOException e) {
                Log.error("Failed to write note '{}'", title, e);
                return FAILED;
            }
        }
    }

    // ==================== show ====================

    @Command(name = "show", description = "Show a note")
    static class Show implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<Title>", description = "Note title")
        String title;

        @Override
        public Integer call() {
            try {
                if (!NoteStorage.exists(title)) {
                    Log.error("Note not found: {}", title);
                    return FAILED;
                }
                NoteRecord note = NoteStorage.load(title);
                IO.println(note.context().stripTrailing());
                return SUCCESSFUL;
            } catch (IllegalArgumentException e) {
                Log.error(e.getMessage());
                return USAGE;
            } catch (IOException e) {
                Log.error("Failed to read note '{}'", title, e);
                return FAILED;
            }
        }
    }

    // ==================== del ====================

    @Command(name = "del", description = "Delete a note")
    static class Del implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<Title>", description = "Note title")
        String title;

        @Override
        public Integer call() {
            try {
                if (!NoteStorage.delete(title)) {
                    Log.error("Note not found: {}", title);
                    return FAILED;
                }
                Log.info("Deleted note '{}'", title.trim());
                return SUCCESSFUL;
            } catch (IllegalArgumentException e) {
                Log.error(e.getMessage());
                return USAGE;
            } catch (IOException e) {
                Log.error("Failed to delete note '{}'", title, e);
                return FAILED;
            }
        }
    }
}
