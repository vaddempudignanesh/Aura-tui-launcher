package ohi.andre.consolelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast in-memory registry of downloads, mirrored to SharedPreferences.
 *
 * Reads are O(1) from RAM (no disk I/O per poll), so the browser's
 * Downloads panel updates smoothly at 4-5 Hz.
 *
 * Writes still go to disk, but debounced: the persist() call is
 * coalesced via a 2-second throttle so we don't thrash storage.
 *
 * Control commands (pause/resume/remove) are dispatched to
 * AuraDownloadService via intents.
 */
public class AuraDownloadHistory {

    public static final String ACTION_CONTROL = "ohi.andre.consolelauncher.DL_CONTROL";
    public static final String EXTRA_ACTION   = "action";    // "pause"|"resume"|"remove"|"cancel"
    public static final String EXTRA_GID      = "gid";

    private static final String PREFS = "aura_downloads";
    private static final String KEY   = "tasks";

    private static AuraDownloadHistory INSTANCE;

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final Map<String, JSONObject> tasks = new ConcurrentHashMap<>();

    private volatile long lastPersistMs = 0L;
    private static final long PERSIST_THROTTLE_MS = 2000L;

    private AuraDownloadHistory(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized AuraDownloadHistory get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new AuraDownloadHistory(ctx);
        return INSTANCE;
    }

    private void load() {
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String gid = o.optString("gid");
                if (!gid.isEmpty()) {
                    // Any non-complete task from a previous session is
                    // marked "paused" until the user resumes it.
                    String s = o.optString("status", "");
                    if ("active".equals(s) || "waiting".equals(s)) {
                        o.put("status", "paused");
                        o.put("downloadSpeed", "0");
                    }
                    tasks.put(gid, o);
                }
            }
        } catch (Exception ignored) {}
    }

    private void persistIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPersistMs < PERSIST_THROTTLE_MS) return;
        lastPersistMs = now;
        persistNow();
    }

    private synchronized void persistNow() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : tasks.values()) arr.put(o);
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void upsert(JSONObject job) {
        String gid = job.optString("gid");
        if (gid.isEmpty()) return;
        tasks.put(gid, job);
        persistIfDue();
    }

    /** Force a disk flush — call on important state changes. */
    public void flush() {
        persistNow();
    }

    public JSONArray listAll() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : tasks.values()) arr.put(o);
        return arr;
    }

    public JSONObject get(String gid) {
        return tasks.get(gid);
    }

    // ─── Control channel: forward commands to AuraDownloadService ────
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
        java.util.Iterator<Map.Entry<String, JSONObject>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JSONObject> e = it.next();
            String s = e.getValue().optString("status", "");
            if (!"active".equals(s)) it.remove();
        }
        flush();
    }
}