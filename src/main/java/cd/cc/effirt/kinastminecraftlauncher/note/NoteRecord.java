package cd.cc.effirt.kinastminecraftlauncher.note;

public record NoteRecord(String title, String context) {
    public NoteRecord {
        title = title == null ? "" : title;
        context = context == null ? "" : context;
    }
}
