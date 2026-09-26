package ohi.andre.consolelauncher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Aria2Manager — single aria2c daemon in RPC mode + JSON-RPC on localhost:6800.
 *
 * v7: builds a CA bundle from Android's system CA store and passes it to aria2c
 * so HTTPS certificate verification works (fixes "unable to get local issuer
 * certificate" failures).
 */
public final class Aria2Manager {

    private static final String TAG = "Aria2Manager";
    private static final String RPC_URL = "http://127.0.0.1:6800/jsonrpc";
    private static final int    RPC_PORT = 6800;

    public static final String DOWNLOAD_DIR = "/storage/emulated/0/Download";

    private static Aria2Manager INSTANCE;

    private Process process;
    private boolean started = false;
    private final Context appContext;

    private boolean staleKillAttempted = false;

    private volatile String lastError = null;

    private Aria2Manager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static synchronized Aria2Manager get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new Aria2Manager(ctx);
        return INSTANCE;
    }

    public String getLastError() {
        return lastError;
    }

    // Add this field to track whether we've tried to kill stale processes this session

    public synchronized boolean ensureStarted() {
        if (started && process != null) return true;   // ← Trust the flag, not isAlive()

        // Only attempt to kill stale daemon ONCE per JVM lifetime
        if (!staleKillAttempted) {
            staleKillAttempted = true;
            killStaleAria2();
        }

        // ── Step 1: get binary path ──────────────────────────────
        String aria2Path = ensureAria2Binary();
        if (aria2Path == null) {
            if (lastError == null) lastError = "aria2c binary not available";
            Log.e(TAG, lastError);
            return false;
        }
        Log.i(TAG, "aria2c path = " + aria2Path);

        // ── Step 2: sanity check binary ──────────────────────────
        try {
            Process test = new ProcessBuilder(aria2Path, "--version")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(test.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) { /* drain */ }
            int exit = test.waitFor();
            if (exit != 0) {
                lastError = "aria2c --version failed (exit " + exit + ")";
                return false;
            }
        } catch (Exception e) {
            lastError = "Cannot execute aria2c: " + e.getMessage();
            Log.e(TAG, lastError, e);
            return false;
        }

        // ── Step 3: CA bundle ────────────────────────────────────
        String caBundle = ensureCaBundle();

        // ── Step 4: launch RPC daemon ────────────────────────────
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(aria2Path);
            cmd.add("--enable-rpc");
            cmd.add("--rpc-listen-all=false");
            cmd.add("--rpc-listen-port=" + RPC_PORT);
            cmd.add("--rpc-allow-origin-all");
            cmd.add("--continue=true");
            cmd.add("--max-connection-per-server=16");
            cmd.add("--split=16");
            cmd.add("--min-split-size=1M");
            cmd.add("--file-allocation=none");
            cmd.add("--console-log-level=warn");
            cmd.add("--summary-interval=0");
            cmd.add("--dir=" + DOWNLOAD_DIR);
            cmd.add("--daemon=false");
            if (caBundle != null) cmd.add("--ca-certificate=" + caBundle);
            cmd.add("--check-certificate=true");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();

            final Process p = process;
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        Log.d(TAG, "aria2c: " + l);
                    }
                } catch (Exception ignored) {}
            }, "aria2-drain").start();

            // Wait for RPC to respond
            for (int i = 0; i < 50; i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                if (rpcCall("aria2.getVersion", new JSONArray())) {
                    lastError = null;
                    started = true;
                    return true;
                }
            }

            lastError = "aria2c RPC did not respond within 5s";
            return false;

        } catch (Exception e) {
            lastError = "start failed: " + e.getMessage();
            Log.e(TAG, lastError, e);
            return false;
        }
    }

    public synchronized void stop() {
        try {
            rpcCall("aria2.shutdown", new JSONArray());
        } catch (Exception ignored) {}
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
            process = null;
        }
        started = false;
    }

    private void killStaleAria2() {
        Log.i(TAG, "killStaleAria2: checking for orphan daemons");

        // First, try RPC shutdown (graceful)
        try {
            if (rpcCall("aria2.getVersion", new JSONArray())) {
                Log.w(TAG, "Found existing aria2c on port " + RPC_PORT + " — shutting it down");
                rpcCall("aria2.shutdown", new JSONArray());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        } catch (Exception ignored) {}

        // Second, force kill any lingering aria2c processes
        try {
            Process ps = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "pkill -f libaria2c.so 2>/dev/null; true"});
            ps.waitFor();
            Thread.sleep(500);
        } catch (Exception ignored) {}

        Log.i(TAG, "killStaleAria2: done");
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    // ═════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════
    public String addDownload(String url, String userAgent, String referer) {
        if (!ensureStarted()) return null;

        try {
            JSONArray params = new JSONArray();
            params.put(new JSONArray().put(url));

            JSONObject options = new JSONObject();
            options.put("dir", DOWNLOAD_DIR);
            options.put("continue", "true");
            options.put("max-connection-per-server", "16");
            options.put("split", "16");
            options.put("file-allocation", "none");
            options.put("check-certificate", "true");
            if (userAgent != null && !userAgent.isEmpty())
                options.put("user-agent", userAgent);
            if (referer != null && !referer.isEmpty())
                options.put("referer", referer);

            params.put(options);

            JSONObject resp = rpc("aria2.addUri", params);
            if (resp != null && resp.has("result")) {
                return resp.getString("result");
            } else if (resp != null && resp.has("error")) {
                JSONObject err = resp.getJSONObject("error");
                Log.e(TAG, "addUri error: " + err.toString());
                lastError = "addUri: " + err.optString("message", "unknown");
            }
        } catch (Exception e) {
            Log.e(TAG, "addDownload failed", e);
        }
        return null;
    }

    public boolean pause(String gid) {
        try {
            JSONArray p = new JSONArray().put(gid);
            JSONObject r = rpc("aria2.pause", p);
            return r != null && r.has("result");
        } catch (Exception e) { return false; }
    }

    public boolean unpause(String gid) {
        try {
            JSONArray p = new JSONArray().put(gid);
            JSONObject r = rpc("aria2.unpause", p);
            return r != null && r.has("result");
        } catch (Exception e) { return false; }
    }

    public boolean remove(String gid) {
        try {
            JSONArray p = new JSONArray().put(gid);
            JSONObject r = rpc("aria2.forceRemove", p);
            return r != null && r.has("result");
        } catch (Exception e) { return false; }
    }

    /** Removes all completed / errored / removed jobs. */
    public void clearStopped() {
        try {
            rpcCall("aria2.purgeDownloadResult", new JSONArray());
        } catch (Exception ignored) {}
    }

    public JSONArray listAll() {
        JSONArray out = new JSONArray();
        try {
            JSONObject active = rpc("aria2.tellActive", new JSONArray());
            int aCount = 0;
            if (active != null && active.has("result")) {
                JSONArray arr = active.getJSONArray("result");
                aCount = arr.length();
                for (int i = 0; i < arr.length(); i++) out.put(arr.getJSONObject(i));
            } else {
                Log.w(TAG, "tellActive returned null or no result: " + active);
            }
            Log.i(TAG, "tellActive → " + aCount + " jobs");

            JSONArray wp = new JSONArray();
            wp.put(0); wp.put(100);
            JSONObject waiting = rpc("aria2.tellWaiting", wp);
            int wCount = 0;
            if (waiting != null && waiting.has("result")) {
                JSONArray arr = waiting.getJSONArray("result");
                wCount = arr.length();
                for (int i = 0; i < arr.length(); i++) out.put(arr.getJSONObject(i));
            }
            Log.i(TAG, "tellWaiting → " + wCount + " jobs");

            JSONArray sp = new JSONArray();
            sp.put(0); sp.put(100);
            JSONObject stopped = rpc("aria2.tellStopped", sp);
            int sCount = 0;
            if (stopped != null && stopped.has("result")) {
                JSONArray arr = stopped.getJSONArray("result");
                sCount = arr.length();
                for (int i = 0; i < arr.length(); i++) out.put(arr.getJSONObject(i));
            } else {
                Log.w(TAG, "tellStopped returned null or no result: " + stopped);
            }
            Log.i(TAG, "tellStopped → " + sCount + " jobs");

            Log.i(TAG, "listAll total → " + out.length() + " jobs");

        } catch (Exception e) {
            Log.e(TAG, "listAll failed", e);
        }
        return out;
    }

    private final Object rpcLock = new Object();

    private JSONObject rpc(String method, JSONArray params) {
        synchronized (rpcLock) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", "aura-" + System.nanoTime());
                body.put("method", method);
                body.put("params", params);

                HttpURLConnection conn = (HttpURLConnection) new URL(RPC_URL).openConnection();
                // ⬇️ DISABLE CONNECTION POOLING (KEEP-ALIVE)
                conn.setRequestProperty("Connection", "close");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("Content-Type", "application/json");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "rpc " + method + " HTTP " + code);
                    conn.disconnect();
                    return null;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                String resp = sb.toString();
                if (resp.length() < 500) {
                    Log.d(TAG, "rpc " + method + " → " + resp);
                } else {
                    Log.d(TAG, "rpc " + method + " → " + resp.substring(0, 500) + "...");
                }

                return new JSONObject(resp);
            } catch (Exception e) {
                Log.e(TAG, "rpc " + method + " failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                return null;
            }
        }
    }

    private boolean rpcCall(String method, JSONArray params) {
        JSONObject r = rpc(method, params);
        return r != null && r.has("result");
    }

    // ═════════════════════════════════════════════════════════════
    //  Binary discovery (nativeLibraryDir preferred, assets fallback)
    // ═════════════════════════════════════════════════════════════
    private String ensureAria2Binary() {
        try {
            String libDir = appContext.getApplicationInfo().nativeLibraryDir;
            if (libDir != null) {
                File bin = new File(libDir, "libaria2c.so");
                Log.i(TAG, "Checking nativeLibraryDir: " + bin.getAbsolutePath());
                if (bin.exists() && bin.canExecute()) {
                    Log.i(TAG, "aria2c found at " + bin.getAbsolutePath()
                            + " (" + bin.length() + " bytes)");
                    lastError = null;
                    return bin.getAbsolutePath();
                }
                Log.w(TAG, "libaria2c.so not at " + bin.getAbsolutePath()
                        + " (exists=" + bin.exists()
                        + " canExecute=" + bin.canExecute() + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "nativeLibraryDir lookup failed", e);
        }

        // Fallback: extract from assets
        File out = new File(appContext.getFilesDir(), "aria2c");
        if (out.exists() && out.length() > 0 && out.canExecute()) {
            Log.i(TAG, "Using fallback filesDir binary: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        }

        try (InputStream is = appContext.getAssets().open("aria2c");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = is.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
            if (total == 0) {
                lastError = "assets/aria2c is empty";
                return null;
            }
            Log.i(TAG, "Extracted fallback binary, " + total + " bytes");
        } catch (Exception e) {
            lastError = "aria2c not found in nativeLibraryDir, "
                    + "and no assets/aria2c fallback (" + e.getClass().getSimpleName() + ")";
            Log.e(TAG, lastError);
            return null;
        }

        if (!out.setExecutable(true, false)) {
            try {
                Runtime.getRuntime().exec(
                        new String[]{"chmod", "755", out.getAbsolutePath()}).waitFor();
            } catch (Exception ignored) {}
        }
        return out.canExecute() ? out.getAbsolutePath() : null;
    }

    // ═════════════════════════════════════════════════════════════
    //  CA bundle (built from Android system CA store)
    // ═════════════════════════════════════════════════════════════
    private String ensureCaBundle() {
        File out = new File(appContext.getFilesDir(), "cacert.pem");
        if (out.exists() && out.length() > 1024) {
            Log.i(TAG, "Reusing existing CA bundle (" + out.length() + " bytes)");
            return out.getAbsolutePath();
        }

        // Android CA locations across versions
        String[] systemDirs = {
                "/system/etc/security/cacerts",              // Android <=13
                "/apex/com.android.conscrypt/cacerts",       // Android 14+
                "/system/ca-certificates",                   // Some ROMs
                "/data/misc/keychain/cacerts-added",         // User-added CAs (root)
        };

        StringBuilder combined = new StringBuilder();
        int certCount = 0;

        for (String dir : systemDirs) {
            File d = new File(dir);
            if (!d.isDirectory() || !d.canRead()) {
                Log.d(TAG, "CA dir not readable: " + dir);
                continue;
            }

            File[] files = d.listFiles();
            if (files == null) continue;

            for (File f : files) {
                if (!f.isFile()) continue;
                try {
                    String content = readFile(f);
                    if (content == null) continue;
                    if (!content.contains("BEGIN CERTIFICATE")) continue;

                    combined.append(content);
                    if (!content.endsWith("\n")) combined.append('\n');
                    combined.append('\n');
                    certCount++;
                } catch (Exception ignored) {}
            }
        }

        if (certCount == 0) {
            Log.w(TAG, "No system CAs found — HTTPS will fail");
            return null;
        }

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(combined.toString().getBytes("UTF-8"));
            Log.i(TAG, "Wrote " + certCount + " CAs → " + out.getAbsolutePath()
                    + " (" + out.length() + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Failed writing CA bundle", e);
            return null;
        }

        return out.getAbsolutePath();
    }

    private static String readFile(File f) {
        try (InputStream is = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}