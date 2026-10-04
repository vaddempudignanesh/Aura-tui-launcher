package ohi.andre.consolelauncher.browser;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Receives chunked base64 data from the page's JS, writes it to a file
 * in Download/, and pushes progress into AuraDownloadHistory so the
 * existing panel/notification keeps working unchanged.
 */
public class AuraBlobBridge {

    private static final String TAG = "AuraBlobBridge";

    private final Context appCtx;

    public AuraBlobBridge(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    @JavascriptInterface
    public void start(String gid, String fileName, long totalSize) {
        try {
            BlobSession s = new BlobSession(gid, fileName, totalSize);
            BLOB_SESSIONS.put(gid, s);

            JSONObject job = new JSONObject();
            job.put("gid", gid);
            job.put("name", fileName);
            job.put("savePath", s.savePath);
            job.put("url", "blob:");
            job.put("status", "active");
            job.put("totalLength", totalSize);
            job.put("completedLength", 0);
            job.put("downloadSpeed", "0");
            job.put("elapsedMs", 0);
            job.put("etaMs", 0);
            AuraDownloadHistory.get(appCtx).upsert(job);
            AuraDownloadHistory.get(appCtx).flush();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
        }
    }

    @JavascriptInterface
    public void chunk(String gid, String base64) {
        BlobSession s = BLOB_SESSIONS.get(gid);
        if (s == null) return;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            s.out.write(bytes);
            s.written += bytes.length;

            long now = System.currentTimeMillis();
            if (now - s.lastReportMs >= 250L) {
                s.lastReportMs = now;
                report(s, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "chunk failed", e);
        }
    }

    @JavascriptInterface
    public void finish(String gid) {
        BlobSession s = BLOB_SESSIONS.remove(gid);
        if (s == null) return;
        try { s.out.flush(); s.out.close(); } catch (Exception ignored) {}

        try {
            JSONObject job = new JSONObject();
            job.put("gid", gid);
            job.put("name", s.fileName);
            job.put("savePath", s.savePath);
            job.put("url", "blob:");
            job.put("status", "complete");
            job.put("totalLength", s.written);
            job.put("completedLength", s.written);
            job.put("downloadSpeed", "0");
            job.put("elapsedMs", System.currentTimeMillis() - s.startMs);
            job.put("etaMs", 0);
            AuraDownloadHistory.get(appCtx).upsert(job);
            AuraDownloadHistory.get(appCtx).flush();
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void error(String gid, String message) {
        BlobSession s = BLOB_SESSIONS.remove(gid);
        if (s != null) {
            try { s.out.close(); } catch (Exception ignored) {}
        }
        try {
            JSONObject job = new JSONObject();
            job.put("gid", gid);
            job.put("name", s != null ? s.fileName : "blob");
            job.put("savePath", s != null ? s.savePath : "");
            job.put("url", "blob:");
            job.put("status", "error");
            job.put("errorMessage", message);
            job.put("totalLength", s != null ? s.totalSize : 0);
            job.put("completedLength", s != null ? s.written : 0);
            AuraDownloadHistory.get(appCtx).upsert(job);
            AuraDownloadHistory.get(appCtx).flush();
        } catch (Exception ignored) {}
    }

    private void report(BlobSession s, boolean done) {
        try {
            long now = System.currentTimeMillis();
            long elapsed = now - s.startMs;
            long speed = elapsed > 0 ? (s.written * 1000L / elapsed) : 0;

            JSONObject job = new JSONObject();
            job.put("gid", s.gid);
            job.put("name", s.fileName);
            job.put("savePath", s.savePath);
            job.put("url", "blob:");
            job.put("status", done ? "complete" : "active");
            job.put("totalLength", s.totalSize);
            job.put("completedLength", s.written);
            job.put("downloadSpeed", String.valueOf(speed));
            job.put("elapsedMs", elapsed);
            job.put("etaMs", speed > 0 && s.totalSize > s.written
                    ? ((s.totalSize - s.written) * 1000L / speed) : 0);
            AuraDownloadHistory.get(appCtx).upsert(job);
        } catch (Exception ignored) {}
    }

    public static String newGid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final java.util.Map<String, BlobSession> BLOB_SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class BlobSession {
        final String gid;
        final String fileName;
        final String savePath;
        final long totalSize;
        final long startMs = System.currentTimeMillis();
        long lastReportMs = 0L;
        long written = 0L;
        OutputStream out;

        BlobSession(String gid, String fileName, long totalSize) throws Exception {
            this.gid = gid;
            this.fileName = fileName;
            this.totalSize = totalSize;

            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            this.savePath = f.getAbsolutePath();
            this.out = new FileOutputStream(f);
        }
    }
}