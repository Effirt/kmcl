package cd.cc.effirt.kinastminecraftlauncher.auth;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record Account(String name, UUID uuid, Type type, JsonObject msaTokens) {

    public enum Type {OFFLINE, MSA}

    public Account {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is blank");
        if (uuid == null) throw new IllegalArgumentException("uuid is null");
        if (type == null) throw new IllegalArgumentException("type is null");
        if (type == Type.MSA && msaTokens == null)
            throw new IllegalArgumentException("MSA account requires tokens");
    }

    public static Account offline(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new Account(name, uuid, Type.OFFLINE, null);
    }

    public static Account msa(String name, UUID uuid, JsonObject tokens) {
        return new Account(name, uuid, Type.MSA, tokens);
    }

    @Override
    public String toString() {
        return "Name=" + name + " (UUID: " + uuid + ", type: " + type + ")";
    }
}