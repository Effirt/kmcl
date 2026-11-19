package cd.cc.effirt.kinastminecraftlauncher.note;

import cd.cc.effirt.auroralib.Log;
import cd.cc.effirt.auroralib.Table;
import cd.cc.effirt.kinastminecraftlauncher.KMCLPath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NoteStorage {

    private static final String EXTENSION = ".txt";

    public static Path noteFile(String title) {
        return KMCLPath.getKmclPath().resolve("notes").resolve(title + EXTENSION);
    }

    public static boolean exists(String title) {
        return Files.isRegularFile(noteFile(title));
    }

    public static String read(String title) throws IOException {
        return Files.readString(noteFile(title), StandardCharsets.UTF_8);
    }

    public static void write(String title, String content) throws IOException {
        Path file = noteFile(title);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** @return 是否真的删除了文件 */
    public static boolean delete(String title) throws IOException {
        return Files.deleteIfExists(noteFile(title));
    }

    /** 所有笔记标题（按名称排序，不含扩展名）。 */
    public static List<String> titles() throws IOException {
        Path dir = KMCLPath.getKmclPath().resolve("notes");
        if (!Files.isDirectory(dir)) return List.of();

        List<String> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(EXTENSION))
                    .map(n -> n.substring(0, n.length() - EXTENSION.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        }
        return out;
    }

    /** 读取一条笔记（标题 + 正文）：{@link NoteRecord#context()} 为文件内容。 */
    public static NoteRecord load(String title) throws IOException {
        return new NoteRecord(title, read(title));
    }

    /** 读取全部笔记（按标题排序，大小写不敏感）。 */
    public static List<NoteRecord> loadAll() throws IOException {
        List<NoteRecord> out = new ArrayList<>();
        for (String title : titles()) {
            out.add(new NoteRecord(title, read(title)));
        }
        return out;
    }

    /** 打印笔记列表表格，格式与 {@code inst list} 一致。 */
    public static void list() {
        List<NoteRecord> notes;
        try {
            notes = loadAll();
        } catch (IOException e) {
            Log.error("Failed to read notes from '{}'", KMCLPath.getKmclPath().resolve("notes"), e);
            return;
        }
        if (notes.isEmpty()) {
            Log.info("No note found");
            return;
        }

        Table table = new Table("#", "Title");
        int i = 0;
        for (NoteRecord note : notes) {
            table.row(String.valueOf(i), note.title());
            i++;
        }
        IO.println(table.render());
    }
}
