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
import java.util.concurrent.atomic.AtomicLong;

public class NativeEngine {
    private static final String TAG = "AURA-NATIVE-DL";

    private final DownloadTaskInfo taskInfo;
    private final String stateFilePath;
    private ExecutorService executor;
    private volatile boolean isPaused = false;
    private final DownloadProgressListener listener;
    private final AtomicLong lastNotify = new AtomicLong(0);

    public interface DownloadProgressListener {
        void onProgress(long totalDownloaded, long totalSize);
        void onComplete();
        void onError(String message);
    }

    public NativeEngine(DownloadTaskInfo taskInfo, DownloadProgressListener listener) {
        this.taskInfo = taskInfo;
        this.stateFilePath = taskInfo.savePath + ".state";
        this.listener = listener;
    }

    public void start() {
        executor = Executors.newFixedThreadPool(taskInfo.threadCount);
        isPaused = false;

        for (int i = 0; i < taskInfo.threadCount; i++) {
            executor.execute(new DownloadWorker(i));
        }

        new Thread(() -> {
            try {
                executor.shutdown();
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                    if (!isPaused) {
                        File stateFile = new File(stateFilePath);
                        if (stateFile.exists()) stateFile.delete();
                        listener.onComplete();
                    }
                }
            } catch (InterruptedException e) {
                listener.onError("Interrupted: " + e.getMessage());
            }
        }).start();
    }

    public void pause() {
        isPaused = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private synchronized void updateProgress() {
        long now = System.currentTimeMillis();
        long total = taskInfo.totalDownloaded();
        listener.onProgress(total, taskInfo.totalSize);
        // Save state at most every 500ms to avoid excessive disk I/O.
        if (now - lastNotify.get() > 500L) {
            lastNotify.set(now);
            taskInfo.saveState(stateFilePath);
        }
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

                in = new BufferedInputStream(connection.getInputStream(), 16384);
                File targetFile = new File(taskInfo.savePath);
                fileAccessor = new RandomAccessFile(targetFile, "rw");
                fileAccessor.seek(actualStart);

                byte[] buffer = new byte[16384];
                int bytesRead;
                while (!isPaused && (bytesRead = in.read(buffer)) != -1) {
                    fileAccessor.write(buffer, 0, bytesRead);
                    taskInfo.downloadedBytes[threadId] += bytesRead;
                    updateProgress();
                }
            } catch (Exception e) {
                Log.e(TAG, "Worker " + threadId + " error: " + e.getMessage());
                if (!isPaused) listener.onError(e.getMessage());
            } finally {
                try { if (fileAccessor != null) fileAccessor.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
            }
        }
    }
}