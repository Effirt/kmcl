package cd.cc.effirt.kinastminecraftlauncher.util;

import java.util.List;

/**
 * 下载源候选：先官方，再用 BMCLAPI 镜像。
 *
 * <p>用于 Forge / NeoForge 的依赖下载：官方仓库（maven.neoforged.net、
 * maven.minecraftforge.net）在部分网络下会因为 DNS 污染或路由不通而超时，
 * 这时自动回退到镜像 {@code https://bmclapi2.bangbang93.com/maven/}。</p>
 */
public class Mirrors {

    public static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";

    /** 官方地址 → 镜像地址，按顺序替换。 */
    private static final List<String[]> MAVEN_MIRRORS = List.of(
            new String[]{"https://maven.neoforged.net/releases/", BMCLAPI_MAVEN},
            new String[]{"http://maven.neoforged.net/releases/", BMCLAPI_MAVEN},
            new String[]{"https://maven.minecraftforge.net/", BMCLAPI_MAVEN},
            new String[]{"http://maven.minecraftforge.net/", BMCLAPI_MAVEN},
            new String[]{"https://libraries.minecraft.net/", "https://bmclapi2.bangbang93.com/libraries/"},
            new String[]{"https://piston-data.mojang.com/", "https://bmclapi2.bangbang93.com/"},
            new String[]{"https://resources.download.minecraft.net/", "https://bmclapi2.bangbang93.com/assets/"}
    );

    /**
     * 返回该 URL 的候选下载地址：原地址（官方优先），若命中可镜像前缀则追加镜像地址。
     */
    public static List<String> candidates(String url) {
        if (url == null || url.isBlank()) return List.of();
        String mirrored = mirror(url);
        if (mirrored == null || mirrored.equals(url)) return List.of(url);
        return List.of(url, mirrored);
    }

    /** 命中镜像前缀时返回替换后的地址，否则返回 {@code null}。 */
    public static String mirror(String url) {
        if (url == null) return null;
        for (String[] pair : MAVEN_MIRRORS) {
            if (url.startsWith(pair[0])) {
                return pair[1] + url.substring(pair[0].length());
            }
        }
        return null;
    }

    /** 是否是可镜像的仓库地址。 */
    public static boolean mirrorable(String url) {
        return mirror(url) != null;
    }
}
