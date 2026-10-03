package ohi.andre.consolelauncher.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AlarmStore {

    private static final String PREF = "ohi_alarm_store";
    private static final String KEY = "alarms";

    public static List<AlarmModel> load(Context c) {
        List<AlarmModel> out = new ArrayList<>();
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(AlarmModel.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static void save(Context c, List<AlarmModel> list) {
        try {
            JSONArray arr = new JSONArray();
            for (AlarmModel m : list) arr.put(m.toJson());
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static void add(Context c, AlarmModel m) {
        List<AlarmModel> list = load(c);
        list.add(m);
        save(c, list);
    }

    public static void update(Context c, AlarmModel m) {
        List<AlarmModel> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == m.id) { list.set(i, m); break; }
        }
        save(c, list);
    }

    public static void delete(Context c, long id) {
        List<AlarmModel> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) list.remove(i);
        }
        save(c, list);
    }

    public static boolean hasActive(Context c) {
        for (AlarmModel m : load(c)) if (m.enabled) return true;
        return false;
    }
}