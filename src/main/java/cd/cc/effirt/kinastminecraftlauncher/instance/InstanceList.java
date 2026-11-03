package cd.cc.effirt.kinastminecraftlauncher.instance;

import cd.cc.effirt.util.Log;
import cd.cc.effirt.util.Table;

import java.io.IOException;
import java.util.Map;

public class InstanceList {
    public static void list() {
        Map<String, InstanceInfo> all;
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

    private static String formatLoader(InstanceInfo info) {
        String loader = info.loader();
        if (loader == null || loader.equals("vanilla")) return "Vanilla";
        String v = info.loaderVersion();
        return switch (loader) {
            case "fabric" -> v != null ? "Fabric " + v : "Fabric";
            case "neoforge" -> v != null ? "NeoForge " + v : "NeoForge";
            case "forge" -> v != null ? "Forge " + v : "Forge";
            default -> loader;
        };
    }
}