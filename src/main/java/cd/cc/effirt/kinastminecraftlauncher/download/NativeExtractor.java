package cd.cc.effirt.kinastminecraftlauncher.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipFile;

public final class NativeExtractor {

    public static void extract(Path jar, Path dest, VersionJSONParser.Extract extract)
            throws IOException {
        var exclude = new ArrayList<String>();
        exclude.add("META-INF/");
        if (extract != null) exclude.addAll(extract.exclude());

        Files.createDirectories(dest);

        try (var zip = new ZipFile(jar.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                var entry = it.nextElement();
                if (entry.isDirectory()) continue;

                var name = entry.getName();
                if (exclude.stream().anyMatch(name::startsWith)) continue;

                int slash = name.lastIndexOf('/');
                var fileName = slash >= 0 ? name.substring(slash + 1) : name;
                if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")) continue;

                var out = dest.resolve(fileName).normalize();
                if (!out.startsWith(dest)) continue;      // 防御

                try (var in = zip.getInputStream(entry);
                     var os = Files.newOutputStream(out)) {
                    in.transferTo(os);
                }
            }
        }
    }
}