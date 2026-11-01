package cd.cc.effirt.kinastminecraftlauncher.minecraft;

import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;
import cd.cc.effirt.util.Log;
import cd.cc.effirt.util.Table;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class InstanceList {
    public static void list() {
        Path root = getVersionsRoot();

        if (!Files.isDirectory(root)) {
            Log.info("No installed instance found");
            return;
        }

        List<InstanceListRecord> entries;
        try {
            entries = collect(root);
        } catch (IOException e) {
            Log.error("Failed to list versions in '{}'", root, e);
            return;
        }

        if (entries.isEmpty()) {
            Log.info("No installed instance found");
            return;
        }

        Table table = new Table("#", "Name", "Minecraft", "Loader");
        int i = 0;
        for (InstanceListRecord e : entries) {
            table.row(String.valueOf(i), e.name(), e.mcVersion(), e.loader());
            i++;
        }
        IO.println(table.render());
    }

    private static List<InstanceListRecord> collect(Path versionsRoot) throws IOException {
        List<InstanceListRecord> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(versionsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String mcVersion = dir.getFileName().toString();
                try (Stream<Path> jsons = Files.list(dir)) {
                    for (Path json : jsons
                            .filter(p -> p.toString().endsWith(".json"))
                            .sorted()
                            .toList()) {
                        String fileName = json.getFileName().toString();
                        String instanceName = fileName.substring(0, fileName.length() - ".json".length());
                        JsonObject obj = readJson(json);

                        // 跳过自动生成的 base JSON
                        if (isBase(obj)) continue;

                        out.add(new InstanceListRecord(
                                instanceName,
                                readMcId(obj, mcVersion),
                                detectLoader(obj)));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(InstanceListRecord::name));
        return out;
    }

    private static JsonObject readJson(Path file) {
        try (var reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException _) {
            return new JsonObject();
        }
    }

    private static String readMcId(JsonObject obj, String fallbackMcVersion) {
        var kmcl = obj.get("kmcl.minecraftVersion");
        if (kmcl != null && !kmcl.isJsonNull()) return kmcl.getAsString();

        var inherits = obj.get("inheritsFrom");
        if (inherits != null && !inherits.isJsonNull()) return inherits.getAsString();

        return fallbackMcVersion;
    }

    /**
     * 优先读安装时写入的 kmcl.loader / kmcl.loaderVersion；
     * 旧 JSON 无字段时 fallback 到 mainClass / libraries 推断。
     */
    private static String detectLoader(JsonObject obj) {
        var l = obj.get("kmcl.loader");
        if (l != null && !l.isJsonNull()) {
            String type = l.getAsString();
            var v = obj.get("kmcl.loaderVersion");
            String ver = (v != null && !v.isJsonNull()) ? v.getAsString() : null;
            return switch (type) {
                case "fabric" -> ver != null ? "Fabric " + ver : "Fabric";
                case "neoforge" -> ver != null ? "NeoForge " + ver : "NeoForge";
                case "forge" -> ver != null ? "Forge " + ver : "Forge";
                default -> "Vanilla";
            };
        }

        return "Vanilla";
    }

    private static Path getVersionsRoot() {
        return KMCLPath.getKmclPath().resolve("versions");
    }

    private static boolean isBase(JsonObject obj) {
        var b = obj.get("kmcl.base");
        return b != null && !b.isJsonNull() && b.getAsBoolean();
    }
}