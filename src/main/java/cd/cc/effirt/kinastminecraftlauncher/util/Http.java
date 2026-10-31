package cd.cc.effirt.kinastminecraftlauncher.util;

import cd.cc.effirt.kinastminecraftlauncher.VersionProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Pattern;

public final class Http {
    //private static final String USER_AGENT = "KinastMinecraftLauncher/" + VersionProvider.getVer();
    //fuck bmcl api。成功改造了userAgent
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 " +
                    "KinastMinecraftLauncher/" + VersionProvider.getVer();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static HttpRequest.Builder builder(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(1))
                .header("User-Agent", USER_AGENT);
    }

    private static final Pattern UNSAFE_FILENAME_CHARS =
            Pattern.compile("[\\r\\n\\t:*?\"<>|]");

    public record FilePayload(String fileName, byte[] bytes) {

        public FilePayload {
            fileName = sanitizeFileName(fileName);
            if (bytes == null) {
                bytes = new byte[0];
            }
        }

        public Path saveTo(Path directory) throws IOException {
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName);
            Files.write(target, bytes);
            return target;
        }

        public int size() {
            return bytes.length;
        }
    }

    public static String get(String url) throws IOException {
        HttpRequest request = builder(url).GET().build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            ensureSuccess(response.statusCode(), url);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GET interrupt: " + url, e);
        }
    }

    public static String get(String url, ProgressBar pb) throws IOException {
        HttpRequest request = builder(url).GET().build();

        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            ensureSuccess(response.statusCode(), url);

            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (contentLength > 0 && pb != null) {
                pb.setTodo(contentLength);
            }

            try (InputStream in = response.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(
                        contentLength > 0 ? (int) contentLength : 64 * 1024);
                byte[] buf = new byte[8192];
                long read = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    read += n;
                    if (pb != null) {
                        pb.setDone(read);
                    }
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GET interrupted: " + url, e);
        }
    }

    public static FilePayload getFile(String url) throws IOException {
        HttpRequest request = builder(url).GET().build();

        try {
            HttpResponse<byte[]> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            ensureSuccess(response.statusCode(), url);
            String disposition = response.headers()
                    .firstValue("Content-Disposition")
                    .orElse(null);
            String fileName = resolveFileName(url, disposition);
            return new FilePayload(fileName, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GET file interrupt: " + url, e);
        }
    }

    public static String getDownload(String url, Path p) throws IOException {
        return getDownload(url, p, null);
    }

    public static String getDownload(String url, Path p, ProgressBar pb) throws IOException {
        Path target = p.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        HttpRequest request = builder(url).GET().build();

        Path temp = null;
        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            ensureSuccess(response.statusCode(), url);

            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (pb != null && contentLength > 0) {
                pb.setTodo(contentLength);
            }

            // 临时文件尽量放在目标同目录，避免跨文件系统 move 失败
            String prefix = target.getFileName().toString();
            temp = (parent != null)
                    ? Files.createTempFile(parent, prefix, ".part")
                    : Files.createTempFile(prefix, ".part");

            long written = 0;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(
                         temp,
                         StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    written += n;
                    if (pb != null) {
                        pb.setDone(written);
                    }
                }
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null; // 已成功移动，避免 finally 误删
            return target.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupt: " + url, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String resolveFileName(String url, String contentDisposition) {
        String fromHeader = parseContentDispositionFileName(contentDisposition);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        String fromPath = fileNameFromUrl(url);
        if (fromPath != null && !fromPath.isBlank()) {
            return fromPath;
        }
        return "download";
    }

    private static String parseContentDispositionFileName(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        String plain = null;
        for (String rawPart : header.split(";")) {
            String part = rawPart.trim();
            if (part.regionMatches(true, 0, "filename*=", 0, 10)) {
                String value = part.substring(10).trim();
                int first = value.indexOf('\'');
                int second = first < 0 ? -1 : value.indexOf('\'', first + 1);
                if (second > 0) {
                    String encoded = value.substring(second + 1);
                    try {
                        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException _) {
                    }
                }
            } else if (plain == null && part.regionMatches(true, 0, "filename=", 0, 9)) {
                String value = part.substring(9).trim();
                if (value.length() >= 2 && value.charAt(0) == '"'
                        && value.charAt(value.length() - 1) == '"') {
                    value = value.substring(1, value.length() - 1);
                }
                plain = value;
            }
        }
        return plain;
    }

    private static String fileNameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            String name = (idx >= 0 ? path.substring(idx + 1) : path).trim();
            return name.isEmpty() ? null : name;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "download";
        }
        String cleaned = UNSAFE_FILENAME_CHARS.matcher(name).replaceAll("_");
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "download";
        }
        return cleaned;
    }

    private static void ensureSuccess(int statusCode, String url) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP " + statusCode + ": " + url);
        }
    }
}