package ohi.andre.consolelauncher.alarm;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ohi.andre.consolelauncher.R;

public class AlarmActivity extends Activity {

    private static final int REQ_RINGTONE = 7001;

    private RecyclerView recycler;
    private TextView emptyView;
    private AlarmAdapter adapter;
    private AlarmModel editing;
    private EditText labelInput;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        recycler = findViewById(R.id.alarm_list);
        emptyView = findViewById(R.id.alarm_empty);
        Button add = findViewById(R.id.alarm_add);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AlarmAdapter(this, this::showEditDialog, this::onToggle);
        recycler.setAdapter(adapter);

        add.setOnClickListener(v -> showEditDialog(null));

        reload();
    }

    private void reload() {
        List<AlarmModel> list = AlarmStore.load(this);
        adapter.setItems(list);
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        if (AlarmStore.hasActive(this)) AlarmService.start(this);
        else AlarmService.stop(this);
    }

    private void onToggle(AlarmModel m) {
        m.enabled = !m.enabled;
        AlarmStore.update(this, m);
        if (m.enabled) AlarmReceiver.schedule(this, m);
        else AlarmReceiver.cancel(this, m);
        reload();
    }

    private void showEditDialog(@Nullable AlarmModel existing) {
        editing = existing != null ? existing : new AlarmModel();
        if (existing == null) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            editing.hour = c.get(java.util.Calendar.HOUR_OF_DAY);
            editing.minute = c.get(java.util.Calendar.MINUTE);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        final TextView timeBtn = new TextView(this);
        styleTitle(timeBtn);
        timeBtn.setText(editing.formatted());
        timeBtn.setGravity(Gravity.CENTER);
        timeBtn.setPadding(dp(20), dp(20), dp(20), dp(20));
        timeBtn.setBackground(getDrawable(R.drawable.bg_card_alarm));
        root.addView(timeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        timeBtn.setOnClickListener(v -> {
            TimePickerDialog tp = new TimePickerDialog(
                    new ContextThemeWrapper(this, R.style.BlackDialog),
                    (view, h, m) -> {
                        editing.hour = h;
                        editing.minute = m;
                        timeBtn.setText(editing.formatted());
                    },
                    editing.hour, editing.minute, true);
            tp.show();
        });

        labelInput = new EditText(this);
        labelInput.setText(editing.label);
        labelInput.setHint("Label");
        labelInput.setTextColor(0xFF00FF00);
        labelInput.setHintTextColor(0xFF00AA00);
        labelInput.setBackgroundColor(0xFF001100);
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        labelInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(labelInput, lp);

        final TextView ringBtn = new TextView(this);
        styleRow(ringBtn);
        ringBtn.setText("Ringtone: " + shortUri(editing.ringtoneUri));
        lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(ringBtn, lp);
        ringBtn.setOnClickListener(v -> {
            Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
            if (editing.ringtoneUri != null && !editing.ringtoneUri.isEmpty()) {
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        Uri.parse(editing.ringtoneUri));
            }
            startActivityForResult(i, REQ_RINGTONE);
        });

        final TextView vibrateBtn = new TextView(this);
        styleRow(vibrateBtn);
        updateVibrateText(vibrateBtn);
        lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(vibrateBtn, lp);
        vibrateBtn.setOnClickListener(v -> {
            editing.vibrate = !editing.vibrate;
            updateVibrateText(vibrateBtn);
        });

        final TextView repeatBtn = new TextView(this);
        styleRow(repeatBtn);
        updateRepeatText(repeatBtn);
        lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(repeatBtn, lp);
        repeatBtn.setOnClickListener(v -> showRepeatDialog(repeatBtn));

        AlertDialog dialog = new AlertDialog.Builder(
                new ContextThemeWrapper(this, R.style.BlackDialog))
                .setTitle(existing == null ? "New alarm" : "Edit alarm")
                .setView(root)
                .setPositiveButton("Save", (d, w) -> saveAlarm())
                .setNeutralButton("Delete", (d, w) -> {
                    if (existing != null) {
                        AlarmReceiver.cancel(this, existing);
                        AlarmStore.delete(this, existing.id);
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    private void updateVibrateText(TextView tv) {
        tv.setText("Vibrate: " + (editing.vibrate ? "ON" : "OFF"));
    }

    private void updateRepeatText(TextView tv) {
        if (editing.repeatDays == null || editing.repeatDays.length == 0) {
            tv.setText("Repeat: Once");
        } else {
            StringBuilder sb = new StringBuilder("Repeat: ");
            String[] names = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
            for (int i = 0; i < editing.repeatDays.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(names[editing.repeatDays[i] % 7]);
            }
            tv.setText(sb.toString());
        }
    }

    private void showRepeatDialog(TextView repeatBtn) {
        final String[] names = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
        final boolean[] checked = new boolean[7];
        if (editing.repeatDays != null) {
            for (int d : editing.repeatDays) if (d >= 0 && d < 7) checked[d] = true;
        }
        new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.BlackDialog))
                .setTitle("Repeat")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    int count = 0;
                    for (boolean b : checked) if (b) count++;
                    editing.repeatDays = new int[count];
                    int i = 0;
                    for (int k = 0; k < 7; k++) if (checked[k]) editing.repeatDays[i++] = k;
                    updateRepeatText(repeatBtn);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveAlarm() {
        editing.label = labelInput.getText().toString().trim();
        AlarmModel existing = null;
        for (AlarmModel m : AlarmStore.load(this)) {
            if (m.id == editing.id) { existing = m; break; }
        }
        if (existing == null) AlarmStore.add(this, editing);
        else AlarmStore.update(this, editing);

        AlarmReceiver.cancel(this, editing);
        if (editing.enabled) AlarmReceiver.schedule(this, editing);
        reload();
    }

    private void styleTitle(TextView tv) {
        tv.setTextColor(0xFF00FF00);
        tv.setTextSize(38);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
    }

    private void styleRow(TextView tv) {
        tv.setTextColor(0xFF00FF00);
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(14), dp(14), dp(14), dp(14));
        tv.setBackground(getDrawable(R.drawable.bg_card_alarm));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String shortUri(String uri) {
        if (uri == null || uri.isEmpty()) return "Default";
        try {
            Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(uri));
            if (r != null) {
                String t = r.getTitle(this);
                if (t != null) return t;
            }
        } catch (Exception ignored) { }
        return uri.length() > 40 ? uri.substring(uri.length() - 40) : uri;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RINGTONE && resultCode == RESULT_OK && data != null) {
            Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            editing.ringtoneUri = u == null ? "" : u.toString();
            Toast.makeText(this, "Ringtone: " + shortUri(editing.ringtoneUri),
                    Toast.LENGTH_SHORT).show();
        }
    }
}