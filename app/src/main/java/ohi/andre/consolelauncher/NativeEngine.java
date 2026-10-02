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

    // ── Raw byte counter, incremented by every worker ────────────────
    private final AtomicLong rawBytesThisTick = new AtomicLong(0);

    // ── EWMA-smoothed speed ──────────────────────────────────────────
    private static final double ALPHA = 0.25;
    private volatile double smoothedSpeed = 0.0;

    // ── Timing ───────────────────────────────────────────────────────
    private long startTimeMs = 0L;
    private long accumulatedMs = 0L;   // elapsed across previous resume sessions

    private Thread tickerThread;

    public interface DownloadProgressListener {
        void onProgress(long totalDownloaded, long totalSize, long bytesPerSec,
                        long elapsedMs, long etaMs);
        void onComplete(long elapsedMs);
        void onError(String message);
        void onPaused();
        void onStarted(long totalSize);
    }

    public NativeEngine(DownloadTaskInfo taskInfo, DownloadProgressListener listener) {
        this.taskInfo = taskInfo;
        this.stateFilePath = taskInfo.savePath + ".state";
        this.listener = listener;
        // Carry over any elapsed time saved across sessions.
        this.accumulatedMs = taskInfo.elapsedMs;
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
        rawBytesThisTick.set(0);
        smoothedSpeed = 0.0;
        startTimeMs = System.currentTimeMillis();

        executor = Executors.newFixedThreadPool(taskInfo.threadCount);

        try { listener.onStarted(taskInfo.totalSize); } catch (Exception ignored) {}

        for (int i = 0; i < taskInfo.threadCount; i++) {
            executor.execute(new DownloadWorker(i));
        }

        tickerThread = new Thread(this::tickerLoop, "aura-dl-ticker");
        tickerThread.setDaemon(true);
        tickerThread.start();

        new Thread(() -> {
            try {
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

                if (tickerThread != null) tickerThread.interrupt();

                long sessionMs = System.currentTimeMillis() - startTimeMs;
                long totalElapsed = accumulatedMs + sessionMs;
                taskInfo.elapsedMs = totalElapsed;
                try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}

                if (paused.get()) {
                    listener.onPaused();
                } else if (!finished.getAndSet(true)) {
                    File stateFile = new File(stateFilePath);
                    if (stateFile.exists()) stateFile.delete();
                    listener.onComplete(totalElapsed);
                }
            } catch (InterruptedException ignored) {}
        }, "aura-dl-watchdog").start();
    }

    private void tickerLoop() {
        long prevTime = System.currentTimeMillis();

        while (!paused.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                return;
            }
            if (paused.get()) return;

            long now = System.currentTimeMillis();
            long elapsed = now - prevTime;
            prevTime = now;

            long deltaBytes = rawBytesThisTick.getAndSet(0);
            double instantSpeed = elapsed > 0
                    ? (deltaBytes * 1000.0) / elapsed
                    : 0.0;

            smoothedSpeed = ALPHA * instantSpeed + (1.0 - ALPHA) * smoothedSpeed;

            long reported = (long) smoothedSpeed;
            if (reported < 1024 && deltaBytes == 0) reported = 0;

            // ── Timing ───────────────────────────────────────────────
            long sessionElapsed = now - startTimeMs;
            long totalElapsed = accumulatedMs + sessionElapsed;

            long downloaded = taskInfo.totalDownloaded();
            long remaining = Math.max(0, taskInfo.totalSize - downloaded);
            long etaMs = 0L;
            if (reported > 0 && remaining > 0) {
                etaMs = (long) ((remaining * 1000.0) / reported);
            }

            try {
                listener.onProgress(downloaded, taskInfo.totalSize,
                        reported, totalElapsed, etaMs);
            } catch (Exception ignored) {}

            // Persist running elapsed time in the state file.
            taskInfo.elapsedMs = totalElapsed;
            try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
        }
    }

    public void pause() {
        if (paused.getAndSet(true)) return;
        // Capture elapsed time before tearing down.
        long sessionMs = System.currentTimeMillis() - startTimeMs;
        taskInfo.elapsedMs = accumulatedMs + sessionMs;
        if (tickerThread != null) tickerThread.interrupt();
        if (executor != null) executor.shutdownNow();
        try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
    }

    private void recordBytes(long bytes) {
        rawBytesThisTick.addAndGet(bytes);
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

                if (actualStart > end) return;

                URL url = new URL(taskInfo.url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
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
                    if (!paused.get()) listener.onError("HTTP " + code);
                    return;
                }

                in = new BufferedInputStream(connection.getInputStream(), 131072);
                File targetFile = new File(taskInfo.savePath);
                fileAccessor = new RandomAccessFile(targetFile, "rw");
                fileAccessor.seek(actualStart);

                byte[] buffer = new byte[131072];
                int bytesRead;
                while (!paused.get() && !Thread.currentThread().isInterrupted()
                        && (bytesRead = in.read(buffer)) != -1) {
                    fileAccessor.write(buffer, 0, bytesRead);
                    taskInfo.downloadedBytes[threadId] += bytesRead;
                    recordBytes(bytesRead);
                }
            } catch (Exception e) {
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