package cd.cc.effirt.kinastminecraftlauncher;

import java.util.Locale;

/**
 * 模组加载器（mod loader）。
 *
 * <p>注意：枚举名 {@code neoforge}、{@code forge}、{@code fabric} 与 {@code instances.json}
 * 中 {@link cd.cc.effirt.kinastminecraftlauncher.instance.InstanceInfoRecord#loader()} 的存储值一致，
 * 改名会破坏已有实例的反序列化。</p>
 */
public enum MinecraftLoaderEnum {
    vanilla, forge, fabric, neoforge;

    /** 用户可读名称，用于命令行描述 / 实例列表。 */
    public String displayName() {
        return switch (this) {
            case vanilla -> "Vanilla";
            case forge -> "Forge";
            case fabric -> "Fabric";
            case neoforge -> "NeoForge";
        };
    }

    /** "--loader" 列表里展示的取值。 */
    public static String tokens() {
        StringBuilder sb = new StringBuilder();
        for (MinecraftLoaderEnum l : values()) {
            if (!sb.isEmpty()) sb.append('/');
            sb.append(l.name());
        }
        return sb.toString();
    }

    /**
     * 宽松解析命令行 / 配置里的加载器名（大小写、空白不敏感）。
     *
     * @throws IllegalArgumentException 未知加载器
     */
    public static MinecraftLoaderEnum fromToken(String token) {
        if (token == null || token.isBlank()) return vanilla;
        String t = token.trim().toLowerCase(Locale.ROOT).replace("-", "")
                .replace("_", "").replace(" ", "");
        return switch (t) {
            case "vanilla" -> vanilla;
            case "forge" -> forge;
            case "fabric" -> fabric;
            case "neoforge", "neo" -> neoforge;
            default -> throw new IllegalArgumentException(
                    "Unknown loader '" + token.trim() + "' (expected one of " + tokens() + ")");
        };
    }
}
