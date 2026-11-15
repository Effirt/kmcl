package cd.cc.effirt.kinastminecraftlauncher.instance.launch;

import java.nio.file.Path;

/** 已探测过的 java 可执行文件（路径 + 主版本号）。 */
public record JavaCandidateRecord(Path path, int major) {
}
