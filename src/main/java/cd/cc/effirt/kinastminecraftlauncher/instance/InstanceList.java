package cd.cc.effirt.kinastminecraftlauncher.instance;

import cd.cc.effirt.auroralib.Log;
import cd.cc.effirt.auroralib.Table;

import java.io.IOException;
import java.util.Map;

public class InstanceList {
    public static void list() {
        Map<String, InstanceInfoRecord> all;
        try {
            all = InstanceRegistry.loadAll();
        } catch (IOException e) {
            Log.error("Failed to read instances.json", e);
            return;
        }

        if (all.isEmpty()) {
            Log.info("No installed instance found");
            return;
        }

        Table table = new Table("#", "Name", "Minecraft", "Loader");
        int i = 0;
        for (var e : all.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            table.row(String.valueOf(i), e.getKey(),
                    e.getValue().minecraftVersion(), formatLoader(e.getValue()));
            i++;
        }
        IO.println(table.render());
    }

    private static String formatLoader(InstanceInfoRecord info) {
        String loader = info.loader();
        if (loader == null || loader.isBlank() || loader.equals("vanilla")) return "Vanilla";
        String v = info.loaderVersion();
        String name = switch (loader) {
            case "fabric" -> "Fabric";
            case "neoforge" -> "NeoForge";
            case "forge" -> "Forge";
            default -> loader;
        };
        return v != null && !v.isBlank() ? name + " " + v : name;
    }
}