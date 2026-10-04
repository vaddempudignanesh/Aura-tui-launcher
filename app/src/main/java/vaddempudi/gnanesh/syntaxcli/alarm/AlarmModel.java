package vaddempudi.gnanesh.syntaxcli.alarm;

import org.json.JSONException;
import org.json.JSONObject;

public class AlarmModel {
    public long id;
    public int hour;    // 0..23 (24-hour internal)
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

    /** HH:mm in 24-hour internal form (used for scheduling). */
    public String formatted24() {
        return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }

    /** hh:mm AM/PM for display in list and edit dialog. */
    public String formattedAmPm() {
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        String ampm = hour < 12 ? "AM" : "PM";
        return String.format(java.util.Locale.US, "%02d:%02d %s", h12, minute, ampm);
    }

    public boolean repeats() {
        return repeatDays != null && repeatDays.length > 0;
    }

    public String repeatSummary() {
        if (!repeats()) return "Once";
        if (repeatDays.length == 7) return "Every day";
        String[] names = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeatDays.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names[repeatDays[i] % 7]);
        }
        return sb.toString();
    }

    /** Formats the "next fire" for the notification body. */
    public String nextFireText() {
        return "Next alarm at " + formattedAmPm();
    }

    /** Kept for backwards compatibility with older call sites. */
    public String formatted() {
        return formattedAmPm();
    }
}