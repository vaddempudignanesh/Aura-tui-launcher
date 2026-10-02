package ohi.andre.consolelauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class AuraDownloadService extends Service {

    public static final String EXTRA_DOWNLOAD_URL        = "DOWNLOAD_URL";
    public static final String EXTRA_USER_AGENT          = "USER_AGENT";
    public static final String EXTRA_CONTENT_DISPOSITION = "CONTENT_DISPOSITION";
    public static final String EXTRA_MIME_TYPE           = "MIME_TYPE";
    public static final String EXTRA_REFERER             = "REFERER";

    private static final String CHANNEL_ID           = "AuraDownloadChannel";
    private static final int    BASE_NOTIFICATION_ID = 8810;

    private static final Map<String, NativeEngine>     ENGINES   = new ConcurrentHashMap<>();
    private static final Map<String, DownloadTaskInfo> INFOS     = new ConcurrentHashMap<>();
    private static final Map<String, Integer>          NOTIF_IDS = new ConcurrentHashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        createNotificationChannel();

        if (AuraDownloadHistory.ACTION_CONTROL.equals(intent.getAction())) {
            String action = intent.getStringExtra(AuraDownloadHistory.EXTRA_ACTION);
            String gid = intent.getStringExtra(AuraDownloadHistory.EXTRA_GID);
            handleControl(action, gid);
            return START_STICKY;
        }

        String urlString = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        if (urlString == null) return START_STICKY;

        String userAgent          = intent.getStringExtra(EXTRA_USER_AGENT);
        String contentDisposition = intent.getStringExtra(EXTRA_CONTENT_DISPOSITION);
        String mimeType           = intent.getStringExtra(EXTRA_MIME_TYPE);
        String referer            = intent.getStringExtra(EXTRA_REFERER);

        String fileName = deriveFileName(urlString, contentDisposition);

        // ★ Per-gid notification ID — ONE notification for the whole
        //   lifecycle of this download, from "Connecting…" all the way
        //   through active, paused, complete.
        String gid = UUID.randomUUID().toString().substring(0, 8);
        int notifId = BASE_NOTIFICATION_ID + Math.abs(gid.hashCode() % 1000);
        NOTIF_IDS.put(gid, notifId);

        startForegroundWithId(notifId, fileName);

        final String finalGid = gid;
        new Thread(() -> initializeDownload(
                finalGid, urlString, userAgent, contentDisposition,
                mimeType, referer, fileName, notifId)).start();

        return START_STICKY;
    }

    private void startForegroundWithId(int notifId, String fileName) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(fileName)
                .setContentText("Connecting…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        startForeground(notifId, n);
    }

    // ═══════════════════════════════════════════════════════════════
    //  START FOREGROUND with the real filename (no "Preparing")
    // ═══════════════════════════════════════════════════════════════

    private void startForegroundWithFileName(String fileName) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(fileName)
                .setContentText("Connecting…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // ★ No .setProgress() at all — no bar shown until we
                //   have real percentage data.
                .build();
        startForeground(BASE_NOTIFICATION_ID, n);
    }    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private String deriveFileName(String urlString, String contentDisposition) {
        String fileName = null;
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
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONTROL
    // ═══════════════════════════════════════════════════════════════

    private void handleControl(String action, String gid) {
        if (action == null || gid == null) return;

        switch (action) {
            case "pause": {
                NativeEngine engine = ENGINES.get(gid);
                if (engine != null) engine.pause();
                // onPaused callback will fire and update notification
                break;
            }
            case "resume": {
                DownloadTaskInfo info = INFOS.get(gid);
                if (info == null) {
                    JSONObject o = AuraDownloadHistory.get(this).get(gid);
                    if (o != null) {
                        String savePath = o.optString("savePath", "");
                        if (!savePath.isEmpty()) {
                            info = DownloadTaskInfo.loadState(savePath + ".state");
                            if (info != null) INFOS.put(gid, info);
                        }
                    }
                }
                if (info != null && !ENGINES.containsKey(gid)) {
                    startEngineFor(info);
                }
                break;
            }
            case "remove": {
                NativeEngine engine = ENGINES.remove(gid);
                if (engine != null) engine.pause();
                DownloadTaskInfo info = INFOS.remove(gid);
                if (info != null) {
                    try { new File(info.savePath).delete(); } catch (Exception ignored) {}
                    try { new File(info.savePath + ".state").delete(); } catch (Exception ignored) {}
                }
                Integer notifId = NOTIF_IDS.remove(gid);
                if (notifId != null) {
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) nm.cancel(notifId);
                }
                break;
            }
        }

        if (ENGINES.isEmpty()) {
            stopForeground(false);
            stopSelf();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  INITIALIZE
    // ═══════════════════════════════════════════════════════════════

    private void initializeDownload(String gid, String urlString, String userAgent,
                                    String contentDisposition, String mimeType,
                                    String referer, String fileName, int notifId) {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            File targetFile = new File(downloadDir, fileName);
            String fullSavePath = targetFile.getAbsolutePath();

            long remoteSize = 0;
            boolean acceptsRanges = false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                if (referer != null) conn.setRequestProperty("Referer", referer);
                conn.connect();
                remoteSize = conn.getContentLengthLong();
                acceptsRanges = "bytes".equalsIgnoreCase(
                        conn.getHeaderField("Accept-Ranges"));
                conn.disconnect();
            } catch (Exception e) {
                Log.w("AuraDL", "HEAD probe failed: " + e.getMessage());
            }

            String statePath = fullSavePath + ".state";
            DownloadTaskInfo info = DownloadTaskInfo.loadState(statePath);

            if (info == null) {
                int threads = (acceptsRanges && remoteSize > 1024 * 1024) ? 4 : 1;
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

                if (remoteSize > 0) {
                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(targetFile, "rw");
                    raf.setLength(remoteSize);
                    raf.close();
                }
                info.saveState(statePath);
            }

            INFOS.put(info.gid, info);
            NOTIF_IDS.put(info.gid, notifId);

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
            AuraDownloadHistory.get(this).flush();

            startEngineFor(info);

        } catch (Exception e) {
            Log.e("AuraDL", "initializeDownload failed", e);
        }
    }

    private void startEngineFor(final DownloadTaskInfo info) {
        final String fileName = new File(info.savePath).getName();

        NativeEngine engine = new NativeEngine(info,
                new NativeEngine.DownloadProgressListener() {

                    @Override
                    public void onStarted(long totalSize) {
                        updateNotification(info.gid, "active",
                                info.totalDownloaded(), 0, fileName,
                                info.elapsedMs, 0);
                    }

                    @Override
                    public void onProgress(long totalDownloaded, long totalSize,
                                           long bytesPerSec, long elapsedMs, long etaMs) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "active");
                            o.put("totalLength", totalSize);
                            o.put("completedLength", totalDownloaded);
                            o.put("downloadSpeed", String.valueOf(bytesPerSec));
                            o.put("elapsedMs", elapsedMs);       // ★ NEW
                            o.put("etaMs", etaMs);               // ★ NEW
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                        } catch (Exception ignored) {}

                        updateNotification(info.gid, "active",
                                totalDownloaded, bytesPerSec, fileName,
                                elapsedMs, etaMs);
                    }

                    @Override
                    public void onComplete(long elapsedMs) {
                        ENGINES.remove(info.gid);
                        INFOS.remove(info.gid);

                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "complete");
                            o.put("totalLength", info.totalSize);
                            o.put("completedLength", info.totalSize);
                            o.put("downloadSpeed", "0");
                            o.put("elapsedMs", elapsedMs);
                            o.put("etaMs", 0);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                            AuraDownloadHistory.get(AuraDownloadService.this).flush();
                        } catch (Exception ignored) {}

                        updateNotification(info.gid, "complete",
                                info.totalSize, 0, fileName,
                                elapsedMs, 0);

                        // ★ Do NOT stopForeground(false) here — the service
                        //   may still be hosting other downloads. Only stop
                        //   when nothing is left.
                        if (ENGINES.isEmpty()) {
                            stopForeground(false);
                            stopSelf();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        ENGINES.remove(info.gid);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "paused");
                            o.put("errorMessage", message);
                            o.put("totalLength", info.totalSize);
                            o.put("completedLength", info.totalDownloaded());
                            o.put("downloadSpeed", "0");
                            o.put("elapsedMs", info.elapsedMs);
                            o.put("etaMs", 0);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                            AuraDownloadHistory.get(AuraDownloadService.this).flush();
                        } catch (Exception ignored) {}

                        updateNotification(info.gid, "error",
                                info.totalDownloaded(), 0, fileName,
                                info.elapsedMs, 0);

                        if (ENGINES.isEmpty()) {
                            stopForeground(false);
                            stopSelf();
                        }
                    }

                    @Override
                    public void onPaused() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "paused");
                            o.put("totalLength", info.totalSize);
                            o.put("completedLength", info.totalDownloaded());
                            o.put("downloadSpeed", "0");
                            o.put("elapsedMs", info.elapsedMs);
                            o.put("etaMs", 0);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                            AuraDownloadHistory.get(AuraDownloadService.this).flush();
                        } catch (Exception ignored) {}

                        ENGINES.remove(info.gid);

                        updateNotification(info.gid, "paused",
                                info.totalDownloaded(), 0, fileName,
                                info.elapsedMs, 0);
                    }
                });

        ENGINES.put(info.gid, engine);
        engine.start();
    }
    // ═══════════════════════════════════════════════════════════════
    //  NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════

    private int notificationIdFor(String gid) {
        Integer id = NOTIF_IDS.get(gid);
        if (id != null) return id;
        int newId = BASE_NOTIFICATION_ID + Math.abs(gid.hashCode() % 1000);
        NOTIF_IDS.put(gid, newId);
        return newId;
    }

    /**
     * Builds a status-aware notification.
     *
     *   active   → small icon: download arrow,     title: name, text: 45% • 2.3 MB/s • 1.8/4 GB, actions: Pause / Cancel
     *   paused   → small icon: pause bar (⏸),      title: name, text: "Paused  •  45%  •  1.8/4 GB",      actions: Resume / Cancel
     *   complete → small icon: checkmark,          title: name, text: "Completed  •  4 GB",              actions: Open
     *   error    → small icon: warning,            title: name, text: "Stopped  •  <error>",             actions: Resume / Cancel
     */
    private void updateNotification(String gid, String status,
                                    long downloaded, long speed, String fileName) {
        updateNotification(gid, status, downloaded, speed, fileName, 0, 0);
    }

    private void updateNotification(String gid, String status,
                                    long downloaded, long speed, String fileName,
                                    long elapsedMs, long etaMs) {
        try {
            JSONObject o = AuraDownloadHistory.get(this).get(gid);
            long total = o != null ? o.optLong("totalLength", 0) : 0;
            if (total <= 0) total = downloaded;

            int pct = total > 0 ? (int) ((downloaded * 100) / total) : 0;

            int smallIcon;
            String contentText;
            boolean ongoing;

            switch (status) {
                case "active":
                    smallIcon = android.R.drawable.stat_sys_download;
                    // Line 1: percent • speed
                    // Line 2: downloaded / total
                    // Line 3: elapsed ↦ ETA
                    contentText = pct + "%  •  " + humanSpeed(speed)
                            + "  •  " + humanBytes(downloaded)
                            + " / " + humanBytes(total)
                            + "  •  " + formatElapsed(elapsedMs)
                            + " left " + formatEta(etaMs);
                    ongoing = true;
                    break;
                case "paused":
                    smallIcon = android.R.drawable.ic_media_pause;
                    contentText = "Paused  •  " + pct + "%  •  "
                            + humanBytes(downloaded) + " / " + humanBytes(total)
                            + "  •  " + formatElapsed(elapsedMs);
                    ongoing = false;
                    break;
                case "complete":
                    smallIcon = android.R.drawable.stat_sys_download_done;
                    contentText = "Completed in " + formatElapsed(elapsedMs)
                            + "  •  " + humanBytes(total);
                    ongoing = false;
                    break;
                default:
                    smallIcon = android.R.drawable.stat_notify_error;
                    String errMsg = o != null ? o.optString("errorMessage", "Network error") : "Error";
                    contentText = "Stopped  •  " + errMsg
                            + "  •  " + formatElapsed(elapsedMs);
                    ongoing = false;
                    break;
            }

            NotificationCompat.Builder b =
                    new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setSmallIcon(smallIcon)
                            .setContentTitle(fileName)
                            .setContentText(contentText)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(ongoing)
                            .setOnlyAlertOnce(true)
                            .setShowWhen(false);

            if ("active".equals(status) || "paused".equals(status)) {
                // ★ Only show a progress bar when we know the total size.
                //   If size is unknown, show NO bar at all — cleaner and
                //   avoids the animated indeterminate slider.
                if (total > 0) {
                    b.setProgress(100, pct, false);
                }
            }
            switch (status) {
                case "active":
                    b.addAction(action("Pause", "pause", gid));
                    b.addAction(action("Cancel", "remove", gid));
                    break;
                case "paused":
                case "error":
                    b.addAction(action("Resume", "resume", gid));
                    b.addAction(action("Cancel", "remove", gid));
                    break;
                case "complete":
                    NotificationCompat.Action openAct =
                            openAction(o != null ? o.optString("savePath", "") : "");
                    if (openAct != null) b.addAction(openAct);
                    break;
            }

            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(notificationIdFor(gid), b.build());

        } catch (Exception ignored) {}
    }

    // ★ New formatters

    private String formatElapsed(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }

    private String formatEta(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60;
        return h + "h " + (m % 60) + "m";
    }

    /**
     * Builds a small monochrome pause icon at runtime and returns it as a
     * resource ID via a Bitmap-based ImageView inside an existing ID.
     *
     * Simplest robust approach: use a drawable we draw ourselves.
     */
    private int makePauseIconRes() {
        // We can't dynamically generate a resource ID, so use Android's
        // built-in media-pause icons for the small icon. It renders fine
        // as a monochrome status bar icon on modern Android.
        return android.R.drawable.ic_media_pause;
    }

    private NotificationCompat.Action action(String label, String cmd, String gid) {
        Intent i = new Intent(this, AuraDownloadService.class);
        i.setAction(AuraDownloadHistory.ACTION_CONTROL);
        i.putExtra(AuraDownloadHistory.EXTRA_ACTION, cmd);
        i.putExtra(AuraDownloadHistory.EXTRA_GID, gid);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getService(
                this, (cmd + gid).hashCode(), i, flags);

        int icon;
        switch (cmd) {
            case "pause":  icon = android.R.drawable.ic_media_pause; break;
            case "resume": icon = android.R.drawable.ic_media_play;  break;
            case "remove": icon = android.R.drawable.ic_menu_close_clear_cancel; break;
            default:       icon = android.R.drawable.ic_menu_more; break;
        }
        return new NotificationCompat.Action(icon, label, pi);
    }

    private NotificationCompat.Action openAction(String savePath) {
        try {
            File f = new File(savePath);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(
                    this, savePath.hashCode(), i, flags);
            return new NotificationCompat.Action(
                    android.R.drawable.ic_menu_view, "Open", pi);
        } catch (Exception e) {
            return null;
        }
    }

    private String humanSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB/s",
                bytesPerSec / (1024.0 * 1024.0));
    }

    private String humanBytes(long b) {
        if (b <= 0) return "0 B";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024L * 1024L)
            return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GB",
                b / (1024.0 * 1024.0 * 1024.0));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        for (NativeEngine engine : ENGINES.values()) engine.pause();
        ENGINES.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}