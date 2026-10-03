package ohi.andre.consolelauncher.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the tab list (URL + incognito flag + active index) so the
 * browser resumes exactly where the user left off after a reboot, crash,
 * process death, or normal close.
 *
 * Incognito tabs are intentionally NOT persisted — that would defeat
 * the purpose of incognito mode.
 */
public final class TabStore {

    private static final String PREFS = "aura_browser_tabs";
    private static final String KEY_TABS = "tabs";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_DARK = "force_dark_global";

    public static final class Snapshot {
        public final List<String> urls = new ArrayList<>();
        public int activeIndex = 0;
    }

    private TabStore() {}

    public static void save(Context ctx, List<String> urls, int activeIndex) {
        try {
            JSONArray arr = new JSONArray();
            for (String u : urls) arr.put(u);

            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            // commit() (not apply()) — must be on disk before the process dies.
            prefs.edit()
                    .putString(KEY_TABS, arr.toString())
                    .putInt(KEY_ACTIVE, Math.max(0, activeIndex))
                    .commit();
        } catch (Exception ignored) {}
    }

    public static Snapshot load(Context ctx) {
        Snapshot snap = new Snapshot();
        try {
            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            String raw = prefs.getString(KEY_TABS, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (u != null && !u.isEmpty()) snap.urls.add(u);
            }
            snap.activeIndex = prefs.getInt(KEY_ACTIVE, 0);
        } catch (Exception ignored) {}
        return snap;
    }

    public static void clear(Context ctx) {
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        } catch (Exception ignored) {}
    }

    // ── Dark-mode preference that survives process death ─────────────
    public static void saveDarkMode(Context ctx, boolean dark) {
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_DARK, dark).commit();
        } catch (Exception ignored) {}
    }

    public static boolean loadDarkMode(Context ctx, boolean fallback) {
        try {
            return ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_DARK, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}