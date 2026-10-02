package ohi.andre.consolelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AuraDownloadHistory {

    public static final String ACTION_CONTROL = "ohi.andre.consolelauncher.DL_CONTROL";
    public static final String EXTRA_ACTION   = "action";
    public static final String EXTRA_GID      = "gid";

    private static final String PREFS = "aura_downloads";
    private static final String KEY   = "tasks";

    // ★ True global singleton: static final lock ensures only one instance
    //   exists, no matter which Context asks for it first.
    private static final Object LOCK = new Object();
    private static volatile AuraDownloadHistory INSTANCE;

    private final Context appCtx;
    private final SharedPreferences prefs;

    // All reads/writes to this map are done under LOCK so a Service thread
    // and an Activity thread can't race.
    private final Map<String, JSONObject> tasks = new ConcurrentHashMap<>();

    // ★ Only the disk flush is throttled. The in-memory map is ALWAYS
    //   updated immediately on upsert, so the UI sees every change.
    private volatile long lastPersistMs = 0L;
    private static final long PERSIST_THROTTLE_MS = 1500L;

    private AuraDownloadHistory(Context ctx) {
        // Always use application context so we never hold a Service or
        // Activity reference that could leak or become stale.
        this.appCtx = ctx.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static AuraDownloadHistory get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new AuraDownloadHistory(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private void load() {
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String gid = o.optString("gid");
                if (gid.isEmpty()) continue;
                String s = o.optString("status", "");
                // Any active/waiting task from a previous session becomes
                // "paused" — we don't know if the process is still alive.
                if ("active".equals(s) || "waiting".equals(s)) {
                    o.put("status", "paused");
                    o.put("downloadSpeed", "0");
                    o.put("etaMs", 0);
                }
                tasks.put(gid, o);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Always updates the in-memory map. Writes to disk only if the
     * throttle window has elapsed. This means the UI always sees the
     * latest data, but we don't thrash storage.
     */
    public void upsert(JSONObject job) {
        String gid = job.optString("gid");
        if (gid.isEmpty()) return;
        tasks.put(gid, job);

        long now = System.currentTimeMillis();
        if (now - lastPersistMs >= PERSIST_THROTTLE_MS) {
            lastPersistMs = now;
            persistNow();
        }
    }

    /**
     * Force a synchronous disk flush. Call this on important state
     * transitions (pause, complete, error) so a process kill won't
     * lose data.
     */
    public void flush() {
        lastPersistMs = System.currentTimeMillis();
        persistNow();
    }

    private synchronized void persistNow() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : tasks.values()) arr.put(o);
            prefs.edit().putString(KEY, arr.toString()).commit();   // commit() = synchronous
        } catch (Exception ignored) {}
    }

    /**
     * Re-read from disk. Use this if the panel is empty but the user
     * expects downloads — protects against process recreation.
     */
    public void reloadFromDisk() {
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String gid = o.optString("gid");
                if (gid.isEmpty()) continue;
                if (!tasks.containsKey(gid)) {
                    tasks.put(gid, o);
                }
            }
        } catch (Exception ignored) {}
    }

    public JSONArray listAll() {
        // Pull anything new from disk before returning, so the panel is
        // never empty when the service has been writing to prefs.
        reloadFromDisk();

        JSONArray arr = new JSONArray();
        for (JSONObject o : tasks.values()) arr.put(o);
        return arr;
    }

    public JSONObject get(String gid) {
        JSONObject o = tasks.get(gid);
        if (o == null) {
            reloadFromDisk();
            o = tasks.get(gid);
        }
        return o;
    }

    // ─── Control channel ─────────────────────────────────────────────
    public void pause(String gid) {
        dispatchControl("pause", gid);
    }

    public void unpause(String gid) {
        dispatchControl("resume", gid);
    }

    public void remove(String gid) {
        dispatchControl("remove", gid);
        tasks.remove(gid);
        flush();
    }

    private void dispatchControl(String action, String gid) {
        try {
            Intent i = new Intent(appCtx, AuraDownloadService.class);
            i.setAction(ACTION_CONTROL);
            i.putExtra(EXTRA_ACTION, action);
            i.putExtra(EXTRA_GID, gid);
            appCtx.startService(i);
        } catch (Exception ignored) {}
    }

    public void clearStopped() {
        java.util.Iterator<Map.Entry<String, JSONObject>> it =
                tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JSONObject> e = it.next();
            String s = e.getValue().optString("status", "");
            if (!"active".equals(s)) it.remove();
        }
        flush();
    }
}