package cd.cc.effirt.kinastminecraftlauncher.util;

import cd.cc.effirt.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadBatch {

    private static final int MAX_CONCURRENCY = 64;

    public record Task(String url, long expectSize, Path dest) {
    }

    public static void one(Task t, int maxAttempts) throws IOException {
        one(t, maxAttempts, null);
    }

    public static void one(Task t, int maxAttempts, ProgressBar pb) throws IOException {
        Files.createDirectories(t.dest().getParent());
        IOException last = null;
        long backoffMs = 100;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Http.getDownload(t.url(), t.dest(), pb);
                if (t.expectSize() > 0 && Files.size(t.dest()) != t.expectSize())
                    throw new IOException("Size mismatch (want " + t.expectSize() + ", got " + Files.size(t.dest()) + ")");
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(t.dest());

                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", ie);
                    }
                    backoffMs = Math.min(backoffMs * 2, 2_000);
                }
            }
        }
        throw last != null ? last : new IOException("Download failed: " + t.url());
    }

    public static void run(List<Task> tasks, int maxAttempts, String label) throws IOException {
        if (tasks.isEmpty()) {
            Log.info("[{}] Is empty", label);
            return;
        }

        var toDo = new ArrayList<Task>(tasks.size());
        int skipped = 0;
        for (var t : tasks) {
            if (Files.exists(t.dest()) &&
                    (t.expectSize() <= 0 || Files.size(t.dest()) == t.expectSize())) {
                skipped++;
                continue;
            }
            toDo.add(t);
        }
        Log.info("[{}] Downloading {} files, {} skip...", label, toDo.size(), skipped);

        int total = toDo.size();
        var done = new AtomicInteger();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var sem = new Semaphore(MAX_CONCURRENCY);

        try (var pb = new ProgressBar()) {
            pb.setTodo(total);
            pb.start();
            if (skipped > 0) {
                pb.finish();
            }
            if (toDo.isEmpty()) {
                pb.finish();
                return;
            }

            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var t : toDo)
                    ex.submit(() -> {
                        try {
                            sem.acquire();
                            try {
                                one(t, maxAttempts);
                            } finally {
                                sem.release();
                            }
                            pb.setCustomText(t.dest().getFileName().toString());
                            pb.setDone(done.incrementAndGet());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            errors.add(e);
                            Log.error("Failed: {}", t.url(), e);
                        }
                    });
            }
        }

        if (!errors.isEmpty())
            throw new IOException("Failed " + errors.size() + "/" + total + " " + label
                    + " downloads; first: " + errors.peek());
    }
}