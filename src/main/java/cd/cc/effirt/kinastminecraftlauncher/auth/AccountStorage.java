package cd.cc.effirt.kinastminecraftlauncher.auth;

import cd.cc.effirt.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class AccountStorage {

    // AccountStorage.java 里新增
    private static final String CURRENT_FILE = ".current";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path dir;

    public AccountStorage(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
    }

    public List<Account> list() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".json")).toList();
        }
        List<Account> result = new ArrayList<>();
        for (Path p : files) {
            try {
                Account acc = read(p);
                if (acc != null) result.add(acc);
            } catch (Exception e) {
                Log.warn("Skip broken account file {}: {}", p, e.getMessage());
            }
        }
        result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    public Optional<Account> load(String name) throws IOException {
        Path p;
        try {
            p = fileFor(name);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!Files.exists(p)) return Optional.empty();
        return Optional.ofNullable(read(p));
    }

    public void save(Account account) throws IOException {
        // 同 UUID 的其他文件（Mojang 改名场景）先删
        Path target = fileFor(account.name());
        List<Path> toDelete = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .filter(f -> !f.getFileName().equals(target.getFileName()))
                    .forEach(f -> {
                        try {
                            Account old = read(f);
                            if (old != null && old.uuid().equals(account.uuid())) toDelete.add(f);
                        } catch (Exception ignored) {}
                    });
        }
        for (Path f : toDelete) Files.deleteIfExists(f);

        Path temp = Files.createTempFile(dir, ".tmp-", ".json");
        try {
            Files.writeString(temp, GSON.toJson(account), StandardCharsets.UTF_8);
            moveAtomically(temp, target);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    public boolean delete(String name) throws IOException {
        Path p;
        try {
            p = fileFor(name);
        } catch (IllegalArgumentException _) {
            return false;
        }
        return Files.deleteIfExists(p);
    }

    private void moveAtomically(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path fileFor(String name) {
        return dir.resolve(sanitize(name) + ".json");
    }

    private Account read(Path p) throws IOException {
        String json = Files.readString(p, StandardCharsets.UTF_8);
        try {
            return GSON.fromJson(json, Account.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Malformed JSON in " + p, e);
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Account name is blank");
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals(".."))
            throw new IllegalArgumentException("Invalid account name: " + name);
        return cleaned;
    }

    public void setCurrent(String name) throws IOException {
        if (!Files.exists(fileFor(name))) {
            throw new IllegalArgumentException("No such account: " + name);
        }
        Files.writeString(dir.resolve(CURRENT_FILE), name, StandardCharsets.UTF_8);
    }

    public Optional<String> getCurrent() throws IOException {
        Path p = dir.resolve(CURRENT_FILE);
        if (!Files.exists(p)) return Optional.empty();
        String name = Files.readString(p, StandardCharsets.UTF_8).trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    public void clearCurrent() throws IOException {
        Files.deleteIfExists(dir.resolve(CURRENT_FILE));
    }
}