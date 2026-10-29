package cd.cc.effirt.kinastminecraftlauncher.minecraft;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionInstaller;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.util.Table;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class InstanceList {

    private static final Path DEFAULT_MC_ROOT = KMCLPath.getKmclPath();

    public static void list() {

        Path root = getVersionsRoot();

        if (!Files.isDirectory(root)) {
            Log.info("No installed instance found");
            return;
        }

        List<String> names;
        try (Stream<Path> s = Files.list(root)) {
            names = s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(InstanceList::isInstalled)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            Log.error("Failed to list versions in '{}'", root, e);
            return;
        }

        if (names.isEmpty()) {
            Log.info("No installed instance found");
            return;
        }

        Table table = new Table("#", "Name", "Minecraft");
        int i = 0;
        for (String name : names) {
            Path dir = VersionInstaller.versionDir(name);
            table.row(String.valueOf(i), name, readMcId(dir.resolve(name + ".json")));
            i++;
        }
        IO.println(table.render());
    }



    private static boolean isInstalled(String name) {
        return Files.isRegularFile(
                VersionInstaller.versionDir(name).resolve(name + ".json"));
    }

    private static String readMcId(Path jsonFile) {
        try (var reader = Files.newBufferedReader(jsonFile)) {
            var obj = JsonParser.parseReader(reader).getAsJsonObject();

            var kmcl = obj.get("kmcl.minecraftVersion");
            if (kmcl != null && !kmcl.isJsonNull()) {
                return kmcl.getAsString();
            }

            var inherits = obj.get("inheritsFrom");
            if (inherits != null && !inherits.isJsonNull()) {
                return inherits.getAsString();
            }

            var id = obj.get("id");
            if (id == null || id.isJsonNull()) return "?";

            return id.getAsString();
        } catch (Exception _) {
            return "?";
        }
    }

    private static Path getVersionsRoot() {
        return DEFAULT_MC_ROOT.resolve("versions");
    }
}
