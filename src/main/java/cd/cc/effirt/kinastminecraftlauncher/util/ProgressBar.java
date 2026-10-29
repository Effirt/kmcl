package cd.cc.effirt.kinastminecraftlauncher.util;

import java.util.concurrent.atomic.AtomicLong;

public class ProgressBar implements AutoCloseable {
    private static final int WIDTH = 20;
    private static final long REFRESH_MS = 111;
    private final Thread thread;
    private volatile String customText = "";
    private volatile long done;
    private volatile long todo;
    private volatile boolean finished;
    private volatile boolean finalLinePrinted;
    private final AtomicLong version = new AtomicLong();

    public ProgressBar() {
        this.thread = createThread();
    }

    public void start() {
        this.thread.start();
    }

    private Thread createThread() {

        Thread t = new Thread(() -> {
            long seenVersion = -1L;

            try {
                while (!finished) {
                    long d = done;
                    long tt = todo;
                    long v = version.get();
                    if (v != seenVersion) {
                        seenVersion = v;
                        if (tt > 0) {
                            IO.print(format(d, tt, customText) + "\r");

                            if (d >= tt) {
                                IO.println();
                                finalLinePrinted = true;
                                return;
                            }
                        }
                    }
                    Thread.sleep(REFRESH_MS);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }, "progressBarThread");
        t.setDaemon(true);
        return t;
    }

    private static String format(long done, long todo, String customText) {
        int filled;
        long pct;
        if (todo > 0) {
            filled = (int) Math.max(0L, Math.min(WIDTH, done * (long) WIDTH / todo));
            pct = done * 100L / todo;
        } else {
            filled = 0;
            pct = 0L;
        }

        StringBuilder sb = new StringBuilder(WIDTH + 32);
        sb.append(String.format("%3d%% ", pct));
        sb.append("█".repeat(filled));
        sb.append("-".repeat(WIDTH - filled));
        if (customText != null && !customText.isEmpty()) {
            sb.append(" ").append(customText).append(" ".repeat(5));
        }
        return sb.toString();
    }

    public void setCustomText(String text) {
        this.customText = (text == null) ? "" : text;
        version.incrementAndGet();
    }

    public void setDone(long done) {
        this.done = done;
        version.incrementAndGet();
    }

    public void setTodo(long todo) {
        this.todo = todo;
        version.incrementAndGet();
    }

    public void finish() {
        finished = true;
        thread.interrupt();
        try {
            thread.join(5555L);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }

        if (finalLinePrinted) {
            return;
        }
        finalLinePrinted = true;
        IO.print(format(done, todo, customText) + System.lineSeparator());
    }

    @Override
    public void close() {
        finish();
    }
}