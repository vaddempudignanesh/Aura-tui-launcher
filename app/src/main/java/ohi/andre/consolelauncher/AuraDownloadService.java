package ohi.andre.consolelauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class AuraDownloadService extends Service {

    public static final String EXTRA_DOWNLOAD_URL       = "DOWNLOAD_URL";
    public static final String EXTRA_USER_AGENT         = "USER_AGENT";
    public static final String EXTRA_CONTENT_DISPOSITION = "CONTENT_DISPOSITION";
    public static final String EXTRA_MIME_TYPE          = "MIME_TYPE";
    public static final String EXTRA_REFERER            = "REFERER";

    private static final String CHANNEL_ID     = "AuraDownloadChannel";
    private static final int    NOTIFICATION_ID = 8812;

    private NativeEngine downloadEngine;
    private DownloadTaskInfo activeTask;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String urlString = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        if (urlString == null) return START_NOT_STICKY;

        String userAgent = intent.getStringExtra(EXTRA_USER_AGENT);
        String contentDisposition = intent.getStringExtra(EXTRA_CONTENT_DISPOSITION);
        String mimeType = intent.getStringExtra(EXTRA_MIME_TYPE);
        String referer = intent.getStringExtra(EXTRA_REFERER);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aura Browser Downloader")
                .setContentText("Preparing download...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> initializeDownload(
                urlString, userAgent, contentDisposition, mimeType, referer)).start();

        return START_STICKY;
    }

    private void initializeDownload(String urlString, String userAgent,
                                    String contentDisposition, String mimeType,
                                    String referer) {
        try {
            // ── Derive filename ───────────────────────────────────────
            String fileName = null;

            // 1. Try Content-Disposition
            if (contentDisposition != null) {
                int idx = contentDisposition.toLowerCase().indexOf("filename=");
                if (idx >= 0) {
                    fileName = contentDisposition.substring(idx + 9).trim();
                    if (fileName.startsWith("\"") && fileName.endsWith("\"")
                            && fileName.length() > 1) {
                        fileName = fileName.substring(1, fileName.length() - 1);
                    }
                }
            }

            // 2. Fall back to URL path
            if (fileName == null || fileName.isEmpty()) {
                String path = urlString;
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                int slash = path.lastIndexOf('/');
                fileName = slash >= 0 ? path.substring(slash + 1) : path;
            }

            if (fileName == null || fileName.isEmpty()) {
                fileName = "download_" + System.currentTimeMillis();
            }

            // ── Destination: /storage/emulated/0/Download ────────────
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            File targetFile = new File(downloadDir, fileName);
            String fullSavePath = targetFile.getAbsolutePath();

            // ── Unique ID for this task ───────────────────────────────
            String gid = UUID.randomUUID().toString().substring(0, 8);

            // ── Probe remote size ─────────────────────────────────────
            long remoteSize = 0;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                if (referer != null) conn.setRequestProperty("Referer", referer);
                conn.connect();
                remoteSize = conn.getContentLengthLong();
                boolean acceptsRanges = "bytes".equalsIgnoreCase(
                        conn.getHeaderField("Accept-Ranges"));
                conn.disconnect();
                if (!acceptsRanges) {
                    // Server doesn't support Range; fall back to single-threaded.
                    remoteSize = remoteSize > 0 ? remoteSize : 0;
                }
            } catch (Exception e) {
                Log.w("AuraDL", "HEAD probe failed: " + e.getMessage());
            }

            // ── Build / restore task info ─────────────────────────────
            String statePath = fullSavePath + ".state";
            DownloadTaskInfo info = DownloadTaskInfo.loadState(statePath);

            if (info == null) {
                int threads = remoteSize > 1024 * 1024 ? 4 : 1; // only split for big files
                info = new DownloadTaskInfo(gid, urlString, fullSavePath,
                        remoteSize, threads);
                info.userAgent = userAgent;
                info.referer = referer;

                if (threads > 1) {
                    long seg = remoteSize / threads;
                    for (int i = 0; i < threads; i++) {
                        info.startBytes[i] = i * seg;
                        info.endBytes[i] = (i == threads - 1)
                                ? remoteSize - 1
                                : info.startBytes[i] + seg - 1;
                        info.downloadedBytes[i] = 0;
                    }
                } else {
                    info.startBytes[0] = 0;
                    info.endBytes[0] = remoteSize > 0 ? remoteSize - 1 : Long.MAX_VALUE - 1;
                    info.downloadedBytes[0] = 0;
                }

                // Pre-allocate file length so RandomAccessFile writes are fast.
                if (remoteSize > 0) {
                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(targetFile, "rw");
                    raf.setLength(remoteSize);
                    raf.close();
                }

                info.saveState(statePath);
            }

            activeTask = info;

            // Register with history
            JSONObject job = new JSONObject();
            job.put("gid", info.gid);
            job.put("name", fileName);
            job.put("savePath", info.savePath);
            job.put("url", info.url);
            job.put("status", "active");
            job.put("totalLength", info.totalSize);
            job.put("completedLength", info.totalDownloaded());
            job.put("downloadSpeed", "0");
            AuraDownloadHistory.get(this).upsert(job);

            // ── Launch engine ─────────────────────────────────────────
            DownloadTaskInfo finalInfo = info;
            String finalFileName = fileName;
            downloadEngine = new NativeEngine(info, new NativeEngine.DownloadProgressListener() {
                long lastBytes = 0;
                long lastTime = System.currentTimeMillis();

                @Override
                public void onProgress(long totalDownloaded, long totalSize) {
                    long now = System.currentTimeMillis();
                    long speed = 0;
                    if (now - lastTime > 500) {
                        speed = (long) ((totalDownloaded - lastBytes)
                                / ((now - lastTime) / 1000.0));
                        lastBytes = totalDownloaded;
                        lastTime = now;
                    }
                    int pct = totalSize > 0
                            ? (int) ((totalDownloaded * 100) / totalSize) : 0;
                    updateNotification("Downloading " + pct + "%  •  "
                            + humanSpeed(speed));
                    try {
                        JSONObject o = new JSONObject();
                        o.put("gid", finalInfo.gid);
                        o.put("name", finalFileName);
                        o.put("savePath", finalInfo.savePath);
                        o.put("url", finalInfo.url);
                        o.put("status", "active");
                        o.put("totalLength", totalSize);
                        o.put("completedLength", totalDownloaded);
                        o.put("downloadSpeed", String.valueOf(speed));
                        AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                    } catch (Exception ignored) {}
                }


                @Override
                public void onComplete() {
                    updateNotification("Download finished: " + finalFileName);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("gid", finalInfo.gid);
                        o.put("name", finalFileName);
                        o.put("savePath", finalInfo.savePath);
                        o.put("url", finalInfo.url);
                        o.put("status", "complete");
                        o.put("totalLength", finalInfo.totalSize);
                        o.put("completedLength", finalInfo.totalSize);
                        o.put("downloadSpeed", "0");
                        AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                    } catch (Exception ignored) {}

                    stopForeground(false);
                    stopSelf();
                }

                @Override
                public void onError(String message) {
                    updateNotification("Paused: " + message);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("gid", finalInfo.gid);
                        o.put("name", finalFileName);
                        o.put("savePath", finalInfo.savePath);
                        o.put("url", finalInfo.url);
                        o.put("status", "error");
                        o.put("errorMessage", message);
                        o.put("totalLength", finalInfo.totalSize);
                        o.put("completedLength", finalInfo.totalDownloaded());
                        o.put("downloadSpeed", "0");
                        AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                    } catch (Exception ignored) {}
                    stopForeground(false);
                    stopSelf();
                }
            });

            downloadEngine.start();

        } catch (Exception e) {
            Log.e("AuraDL", "initializeDownload failed", e);
            updateNotification("Error: " + e.getMessage());
            stopSelf();
        }
    }

    private String humanSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "—";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB/s",
                bytesPerSec / (1024.0 * 1024.0));
    }

    private void updateNotification(String msg) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aura Downloader")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        manager.notify(NOTIFICATION_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (downloadEngine != null) downloadEngine.pause();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}