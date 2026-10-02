package ohi.andre.consolelauncher;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NativeEngine {
    private static final String TAG = "AURA-NATIVE-DL";

    private final DownloadTaskInfo taskInfo;
    private final String stateFilePath;
    private ExecutorService executor;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final DownloadProgressListener listener;

    // ── Speed tracking (all updates via AtomicLong to be thread-safe) ──
    private final AtomicLong windowBytes = new AtomicLong(0);   // bytes in current window
    private final AtomicLong windowStart = new AtomicLong(0);   // window start time
    private final AtomicLong currentSpeed = new AtomicLong(0);  // last computed B/s

    private Thread tickerThread;

    public interface DownloadProgressListener {
        void onProgress(long totalDownloaded, long totalSize, long bytesPerSec);
        void onComplete();
        void onError(String message);
        void onPaused();
    }

    public NativeEngine(DownloadTaskInfo taskInfo, DownloadProgressListener listener) {
        this.taskInfo = taskInfo;
        this.stateFilePath = taskInfo.savePath + ".state";
        this.listener = listener;
    }

    public DownloadTaskInfo getTaskInfo() {
        return taskInfo;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void start() {
        paused.set(false);
        finished.set(false);

        executor = Executors.newFixedThreadPool(taskInfo.threadCount);

        // Reset speed window
        windowBytes.set(0);
        windowStart.set(System.currentTimeMillis());
        currentSpeed.set(0);

        // Launch the worker threads
        for (int i = 0; i < taskInfo.threadCount; i++) {
            executor.execute(new DownloadWorker(i));
        }

        // Launch the speed ticker (fires onProgress every 500 ms)
        tickerThread = new Thread(this::tickerLoop, "aura-dl-ticker");
        tickerThread.setDaemon(true);
        tickerThread.start();

        // Watchdog to fire onComplete when all workers exit
        new Thread(() -> {
            try {
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

                // Stop the ticker
                if (tickerThread != null) tickerThread.interrupt();

                if (paused.get()) {
                    // Pause path: emit final onPaused
                    try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
                    listener.onPaused();
                } else if (!finished.getAndSet(true)) {
                    // Natural completion
                    File stateFile = new File(stateFilePath);
                    if (stateFile.exists()) stateFile.delete();
                    listener.onComplete();
                }
            } catch (InterruptedException e) {
                // fall through
            }
        }, "aura-dl-watchdog").start();
    }

    /**
     * Runs while not paused/terminated. Every 500 ms it computes the
     * rolling speed and forwards onProgress. This guarantees the UI
     * always sees a fresh value (0 B/s if truly stalled).
     */
    private void tickerLoop() {
        while (!paused.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                return;
            }
            if (paused.get()) return;

            long now = System.currentTimeMillis();
            long elapsed = now - windowStart.get();
            if (elapsed >= 1000) {
                long bytes = windowBytes.getAndSet(0);
                long speed = (long) (bytes / (elapsed / 1000.0));
                currentSpeed.set(speed);
                windowStart.set(now);
            }

            // Emit progress (speed is refreshed every ~1 s, total every 500 ms)
            long total = taskInfo.totalDownloaded();
            try {
                listener.onProgress(total, taskInfo.totalSize, currentSpeed.get());
            } catch (Exception ignored) {}

            // Persist state
            try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
        }
    }

    /**
     * Pauses all workers, flushes state, and notifies the listener.
     * Safe to call from any thread.
     */
    public void pause() {
        if (paused.getAndSet(true)) return;   // already paused
        if (tickerThread != null) tickerThread.interrupt();
        if (executor != null) {
            executor.shutdownNow();            // interrupts each worker
        }
        try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
    }

    /**
     * Called by each worker on every chunk. Just adds to the counters.
     * Speed computation happens in the ticker.
     */
    private void recordBytes(long bytes) {
        windowBytes.addAndGet(bytes);
    }

    private class DownloadWorker implements Runnable {
        private final int threadId;

        DownloadWorker(int threadId) {
            this.threadId = threadId;
        }

        @Override
        public void run() {
            HttpURLConnection connection = null;
            RandomAccessFile fileAccessor = null;
            BufferedInputStream in = null;

            try {
                long actualStart = taskInfo.startBytes[threadId]
                        + taskInfo.downloadedBytes[threadId];
                long end = taskInfo.endBytes[threadId];

                if (actualStart > end) {
                    Log.d(TAG, "Worker " + threadId + " already complete");
                    return;
                }

                URL url = new URL(taskInfo.url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Range",
                        "bytes=" + actualStart + "-" + end);
                if (taskInfo.userAgent != null && !taskInfo.userAgent.isEmpty()) {
                    connection.setRequestProperty("User-Agent", taskInfo.userAgent);
                }
                if (taskInfo.referer != null && !taskInfo.referer.isEmpty()) {
                    connection.setRequestProperty("Referer", taskInfo.referer);
                }
                connection.connect();

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_PARTIAL
                        && code != HttpURLConnection.HTTP_OK) {
                    listener.onError("HTTP " + code);
                    return;
                }

                in = new BufferedInputStream(connection.getInputStream(), 8192);
                File targetFile = new File(taskInfo.savePath);
                fileAccessor = new RandomAccessFile(targetFile, "rw");
                fileAccessor.seek(actualStart);

                // ★ 8 KB buffer → pause takes effect within ~1 read cycle
                byte[] buffer = new byte[8192];
                int bytesRead;
                while (!paused.get() && !Thread.currentThread().isInterrupted()
                        && (bytesRead = in.read(buffer)) != -1) {
                    fileAccessor.write(buffer, 0, bytesRead);
                    taskInfo.downloadedBytes[threadId] += bytesRead;
                    recordBytes(bytesRead);
                }

                Log.d(TAG, "Worker " + threadId + " exiting, paused=" + paused.get());

            } catch (Exception e) {
                // Only report errors that aren't caused by our own interrupt
                if (!paused.get() && !Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Worker " + threadId + " error: " + e.getMessage());
                    listener.onError(e.getMessage() == null ? "Network error" : e.getMessage());
                }
            } finally {
                try { if (fileAccessor != null) fileAccessor.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
            }
        }
    }
}