package cd.cc.effirt.kinastminecraftlauncher.minecraft;

import cd.cc.effirt.util.Log;
import cd.cc.effirt.kinastminecraftlauncher.download.VersionManifest;
import cd.cc.effirt.util.Table;

public final class MinecraftVersionList {

    private static final String[] HEADERS = { "ID", "Type", "Release Time" };

    private MinecraftVersionList() {}

    /** 全部版本，最新在前（对应命令的 -a/--all）。 */
    public static void printAll() {
        print(VersionManifest.getAll());
    }

    /** 打印单条版本（null 表示获取失败）。 */
    public static void printSingle(String[] row) {
        if (row == null) {
            Log.error("Failed to fetch version manifest from Mojang.");
            return;
        }
        print(new String[][]{ row });
    }

    /** 只打印正式版（release）。 */
    public static void printRelease() {
        print(VersionManifest.getReleaseAll());
    }

    /** 只打印快照（snapshot）。 */
    public static void printSnapshot() {
        print(VersionManifest.getSnapshotAll());
    }

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
}