package ohi.andre.consolelauncher.alarm;

import org.json.JSONException;
import org.json.JSONObject;

public class AlarmModel {
    public long id;
    public int hour;
    public int minute;
    public String label;
    public String ringtoneUri;
    public boolean enabled;
    public boolean vibrate;
    public int[] repeatDays; // 0=Sun..6=Sat, empty = once

    public AlarmModel() {
        this.id = System.currentTimeMillis();
        this.enabled = true;
        this.vibrate = true;
        this.ringtoneUri = "";
        this.label = "";
        this.repeatDays = new int[0];
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("hour", hour);
        o.put("minute", minute);
        o.put("label", label == null ? "" : label);
        o.put("ringtone", ringtoneUri == null ? "" : ringtoneUri);
        o.put("enabled", enabled);
        o.put("vibrate", vibrate);
        org.json.JSONArray arr = new org.json.JSONArray();
        if (repeatDays != null) for (int d : repeatDays) arr.put(d);
        o.put("repeat", arr);
        return o;
    }

    public static AlarmModel fromJson(JSONObject o) {
        AlarmModel m = new AlarmModel();
        m.id = o.optLong("id", System.currentTimeMillis());
        m.hour = o.optInt("hour", 0);
        m.minute = o.optInt("minute", 0);
        m.label = o.optString("label", "");
        m.ringtoneUri = o.optString("ringtone", "");
        m.enabled = o.optBoolean("enabled", true);
        m.vibrate = o.optBoolean("vibrate", true);
        org.json.JSONArray arr = o.optJSONArray("repeat");
        if (arr != null) {
            m.repeatDays = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) m.repeatDays[i] = arr.optInt(i, 0);
        } else {
            m.repeatDays = new int[0];
        }
        return m;
    }

    public String formatted() {
        return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }

    public boolean repeats() {
        return repeatDays != null && repeatDays.length > 0;
    }
}