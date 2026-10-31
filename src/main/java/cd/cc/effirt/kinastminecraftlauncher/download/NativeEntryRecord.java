package cd.cc.effirt.kinastminecraftlauncher.download;

import java.nio.file.Path;

public record NativeEntryRecord(Path jar, VersionJSONParser.Extract extract) {
}
