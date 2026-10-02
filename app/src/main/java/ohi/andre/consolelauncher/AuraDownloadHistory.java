package ohi.andre.consolelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory + SharedPreferences-backed history of downloads.
 * Used by the Downloads panel in the browser.
 */
public class AuraDownloadHistory {

    private static final String PREFS = "aura_downloads";
    private static final String KEY   = "tasks";

    private static AuraDownloadHistory INSTANCE;
    private final SharedPreferences prefs;
    private final Map<String, JSONObject> tasks = new ConcurrentHashMap<>();

    private AuraDownloadHistory(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
                tasks.put(o.optString("gid"), o);
            }
        } catch (Exception ignored) {}
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : tasks.values()) arr.put(o);
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public synchronized void upsert(JSONObject job) {
        tasks.put(job.optString("gid"), job);
        persist();
    }

    public synchronized JSONArray listAll() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : tasks.values()) arr.put(o);
        return arr;
    }

    public synchronized void pause(String gid) {
        // Handled by AuraDownloadService via broadcast; here we just mark.
        try {
            JSONObject o = tasks.get(gid);
            if (o != null) { o.put("status", "paused"); persist(); }
        } catch (Exception ignored) {}
    }

    public synchronized void unpause(String gid) {
        try {
            JSONObject o = tasks.get(gid);
            if (o != null) { o.put("status", "waiting"); persist(); }
        } catch (Exception ignored) {}
    }

    public synchronized void remove(String gid) {
        tasks.remove(gid);
        persist();
    }

    public synchronized void clearStopped() {
        java.util.Iterator<Map.Entry<String, JSONObject>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JSONObject> e = it.next();
            String s = e.getValue().optString("status", "");
            if (!"active".equals(s)) it.remove();
        }
        persist();
    }
}