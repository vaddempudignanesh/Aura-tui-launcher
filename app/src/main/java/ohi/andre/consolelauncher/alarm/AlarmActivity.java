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
import android.widget.FrameLayout;
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

    private LinearLayout selectionBar;
    private TextView selectionCount;
    private TextView selectDelete, selectEnable, selectDisable, selectClose;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        recycler = findViewById(R.id.alarm_list);
        emptyView = findViewById(R.id.alarm_empty);
        Button add = findViewById(R.id.alarm_add);

        selectionBar = findViewById(R.id.alarm_selection_bar);
        selectionCount = findViewById(R.id.alarm_selection_count);
        selectDelete = findViewById(R.id.alarm_select_delete);
        selectEnable = findViewById(R.id.alarm_select_enable);
        selectDisable = findViewById(R.id.alarm_select_disable);
        selectClose = findViewById(R.id.alarm_select_close);        View alarmArea = findViewById(R.id.alarm_area);
        View stopwatchView = findViewById(R.id.stopwatch_view);
        View timerView = findViewById(R.id.timer_view);
        TextView tabAlarm = findViewById(R.id.tab_alarm);
        TextView tabStopwatch = findViewById(R.id.tab_stopwatch);
        TextView tabTimer = findViewById(R.id.tab_timer);

        Runnable showAlarmTab = () -> {
            recycler.setVisibility(View.VISIBLE);
            stopwatchView.setVisibility(View.GONE);
            timerView.setVisibility(View.GONE);
            add.setVisibility(View.VISIBLE);
            emptyView.setVisibility(adapter.getItemCount() == 0
                    ? View.VISIBLE : View.GONE);
            tabAlarm.setTextColor(0xFF00FF00);
            tabStopwatch.setTextColor(0xFF00AA00);
            tabTimer.setTextColor(0xFF00AA00);
        };
        Runnable showStopwatchTab = () -> {
            recycler.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            stopwatchView.setVisibility(View.VISIBLE);
            timerView.setVisibility(View.GONE);
            add.setVisibility(View.GONE);
            tabAlarm.setTextColor(0xFF00AA00);
            tabStopwatch.setTextColor(0xFF00FF00);
            tabTimer.setTextColor(0xFF00AA00);
        };
        Runnable showTimerTab = () -> {
            recycler.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            stopwatchView.setVisibility(View.GONE);
            timerView.setVisibility(View.VISIBLE);
            add.setVisibility(View.GONE);
            tabAlarm.setTextColor(0xFF00AA00);
            tabStopwatch.setTextColor(0xFF00AA00);
            tabTimer.setTextColor(0xFF00FF00);
        };

        tabAlarm.setOnClickListener(v -> showAlarmTab.run());
        tabStopwatch.setOnClickListener(v -> showStopwatchTab.run());
        tabTimer.setOnClickListener(v -> showTimerTab.run());

        setupStopwatch();
        setupTimer();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AlarmAdapter(this,
                this::showEditDialog,
                this::onToggle,
                this::onSelectionChanged);
        recycler.setAdapter(adapter);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) { }
        }

        add.setOnClickListener(v -> showEditDialog(null));

        selectDelete.setOnClickListener(v -> {
            adapter.deleteSelected();
            reload();
        });
        selectEnable.setOnClickListener(v -> {
            adapter.enableSelected(true);
            adapter.clearSelection();
            reload();
        });
        selectDisable.setOnClickListener(v -> {
            adapter.enableSelected(false);
            adapter.clearSelection();
            reload();
        });
        selectClose.setOnClickListener(v -> adapter.clearSelection());

        reload();
    }

    // ---------------- Stopwatch ----------------
    private android.os.Handler swHandler;
    private long swStart = 0L;
    private long swElapsed = 0L;
    private boolean swRunning = false;

    private void setupStopwatch() {
        swHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        TextView swTime = findViewById(R.id.stopwatch_time);
        Button start = findViewById(R.id.stopwatch_start);
        Button reset = findViewById(R.id.stopwatch_reset);

        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!swRunning) return;
                long total = swElapsed + (System.currentTimeMillis() - swStart);
                swTime.setText(formatStopwatch(total));
                swHandler.postDelayed(this, 100);
            }
        };

        start.setOnClickListener(v -> {
            if (swRunning) {
                // PAUSE
                swRunning = false;
                swElapsed += System.currentTimeMillis() - swStart;
                swHandler.removeCallbacksAndMessages(null);
                start.setText("RESUME");
                swTime.setText(formatStopwatch(swElapsed));
            } else {
                // START or RESUME
                boolean isResume = swElapsed > 0L;
                swRunning = true;
                swStart = System.currentTimeMillis();
                start.setText("PAUSE");
                swHandler.post(tick);
                if (!isResume) {
                    // fresh start: ensure display starts at zero
                    swTime.setText(formatStopwatch(0L));
                }
            }
        });

        reset.setOnClickListener(v -> {
            swRunning = false;
            swHandler.removeCallbacksAndMessages(null);
            swElapsed = 0L;
            swTime.setText(formatStopwatch(0L));
            start.setText("START");
        });

        swTime.setText(formatStopwatch(0L));
    }

    private static String formatStopwatch(long ms) {
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        long tenths = (ms % 1000) / 100;
        return String.format(java.util.Locale.US,
                "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths);
    }

    private android.os.CountDownTimer timer;
    private boolean timerRunning = false;   // actively counting
    private boolean timerPaused  = false;   // frozen mid-count
    private long timerRemainingMs = 0L;     // only meaningful while paused

    private void setupTimer() {
        TextView tvTime = findViewById(R.id.timer_time);
        EditText inH = findViewById(R.id.timer_hours);
        EditText inM = findViewById(R.id.timer_minutes);
        EditText inS = findViewById(R.id.timer_seconds);
        Button start = findViewById(R.id.timer_start);
        Button cancel = findViewById(R.id.timer_cancel);

        start.setOnClickListener(v -> {
            if (timerRunning) {
                // ── PAUSE ──
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                }
                timerRunning = false;
                timerPaused = true;
                start.setText("RESUME");
                cancel.setEnabled(true);
                return;
            }

            if (timerPaused) {
                // ── RESUME ──
                timerPaused = false;
                timerRunning = true;
                start.setText("PAUSE");
                startCountDown(timerRemainingMs, tvTime, start, cancel);
                return;
            }

            // ── START (fresh) ──
            int hours = parseIntSafe(inH);
            int minutes = parseIntSafe(inM);
            int seconds = parseIntSafe(inS);

            if (hours < 0 || minutes < 0 || seconds < 0) {
                Toast.makeText(this, "Enter valid numbers", Toast.LENGTH_SHORT).show();
                return;
            }
            if (minutes > 59 || seconds > 59) {
                Toast.makeText(this, "Minutes and seconds must be 0-59",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            long totalMs = ((long) hours * 3600L
                    + (long) minutes * 60L
                    + seconds) * 1000L;

            if (totalMs <= 0) {
                Toast.makeText(this, "Enter a time greater than 0",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            timerRunning = true;
            timerPaused = false;
            timerRemainingMs = totalMs;
            start.setText("PAUSE");
            cancel.setEnabled(true);
            tvTime.setText(formatTimer(totalMs));
            startCountDown(totalMs, tvTime, start, cancel);
        });

        cancel.setOnClickListener(v -> {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            timerRunning = false;
            timerPaused = false;
            timerRemainingMs = 0L;
            start.setText("START");
            tvTime.setText("00:00:00");
            // Reset input fields
            inH.setText("");
            inM.setText("");
            inS.setText("");
        });

        cancel.setEnabled(false);
        tvTime.setText("00:00:00");
    }

    private void startCountDown(long totalMs, TextView tvTime, Button start, Button cancel) {
        timer = new android.os.CountDownTimer(totalMs, 250L) {
            @Override public void onTick(long millisUntilFinished) {
                timerRemainingMs = millisUntilFinished;
                tvTime.setText(formatTimer(millisUntilFinished));
            }

            @Override public void onFinish() {
                timerRunning = false;
                timerPaused = false;
                timerRemainingMs = 0L;
                start.setText("START");
                cancel.setEnabled(false);
                tvTime.setText("00:00:00");
                fireTimerActivity();
            }
        };
        timer.start();
    }

    private void fireTimerActivity() {
        Intent i = new Intent(this, TimerRingActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private int parseIntSafe(EditText et) {
        try {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) return 0;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }





    private static String formatTimer(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        return String.format(java.util.Locale.US,
                "%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) timer.cancel();
        if (swHandler != null) swHandler.removeCallbacksAndMessages(null);
    }

    private void onSelectionChanged(int count) {
        if (count == 0) {
            selectionBar.setVisibility(View.GONE);
        } else {
            selectionBar.setVisibility(View.VISIBLE);
            selectionCount.setText(count + " selected");
        }
    }

    private void reload() {
        List<AlarmModel> list = AlarmStore.load(this);
        adapter.setItems(list);
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        onSelectionChanged(adapter.getSelectedIds().size());
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
        timeBtn.setText(editing.formattedAmPm());
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
                        timeBtn.setText(editing.formattedAmPm());
                    },
                    editing.hour, editing.minute, false); // false = 12-hour AM/PM picker
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

        new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.BlackDialog))
                .setTitle(existing == null ? "New alarm" : "Edit alarm")
                .setView(root)
                .setPositiveButton("Save", (d, w) -> saveAlarm())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateVibrateText(TextView tv) {
        tv.setText("Vibrate: " + (editing.vibrate ? "ON" : "OFF"));
    }

    private void updateRepeatText(TextView tv) {
        tv.setText("Repeat: " + editing.repeatSummary());
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