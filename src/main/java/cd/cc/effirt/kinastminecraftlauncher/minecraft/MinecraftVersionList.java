package cd.cc.effirt.kinastminecraftlauncher.minecraft;

import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionManifest;
import cd.cc.effirt.util.Table;

import java.util.Set;

public class MinecraftVersionList {

    public static void print(String[][] rows) {
        if (rows == null) {
            Log.error("Failed to fetch version manifest from Mojang.");
            return;
        }
        if (rows.length == 0) {
            Log.error("Version manifest contained no matching entries.");
            return;
        }

        Table table = new Table("ID", "Type", "Release Time");
        for (String[] row : rows) {
            table.row(
                    row[VersionManifest.ID],
                    row[VersionManifest.TYPE],
                    row[VersionManifest.RELEASE_TIME]
            );
        }
        IO.println(table.render());
    }

    /** 按 type 集合打印全部匹配项（-a）。 */
    public static void printByTypes(Set<String> types) {
        print(VersionManifest.getAllTyped(types));
    }

    /** 按 type 集合打印每类最新一条（-l）。 */
    public static void printLatestByTypes(Set<String> types) {
        print(VersionManifest.getLatestTyped(types));
    }
}