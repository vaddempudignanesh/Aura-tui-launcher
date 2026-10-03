package ohi.andre.consolelauncher.alarm;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import ohi.andre.consolelauncher.R;

public class AlarmRingActivity extends Activity {

    private MediaPlayer player;
    private Vibrator vibrator;
    private long alarmId = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(R.layout.activity_alarm_ring);

        alarmId = getIntent().getLongExtra(AlarmReceiver.EXTRA_ID, -1);

        TextView time = findViewById(R.id.ring_time);
        TextView label = findViewById(R.id.ring_label);
        Button stop = findViewById(R.id.ring_stop);

        AlarmModel m = null;
        for (AlarmModel a : AlarmStore.load(this)) {
            if (a.id == alarmId) { m = a; break; }
        }

        if (m != null) {
            time.setText(m.formatted());
            label.setText(m.label == null || m.label.isEmpty() ? "Alarm" : m.label);
        } else {
            time.setText("--:--");
            label.setText("Alarm");
        }

        startRinging(m);

        stop.setOnClickListener(v -> {
            stopRinging();
            finish();
        });
    }

    private void startRinging(AlarmModel m) {
        try {
            Uri uri = null;
            if (m != null && m.ringtoneUri != null && !m.ringtoneUri.isEmpty()) {
                uri = Uri.parse(m.ringtoneUri);
            }
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);

            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) { }

        boolean wantVibrate = m == null || m.vibrate;
        if (wantVibrate) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = { 0, 800, 600, 800, 600 };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        }

        // If the user doesn't stop it, ring for 5 minutes then auto-stop.
        handler.postDelayed(this::autoStop, 5 * 60 * 1000L);
    }

    private void autoStop() {
        stopRinging();
        finish();
    }

    private void stopRinging() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (player != null) {
                if (player.isPlaying()) player.stop();
                player.release();
            }
        } catch (Exception ignored) { }
        player = null;

        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) { }
        vibrator = null;

        if (!AlarmStore.hasActive(this)) AlarmService.stop(this);
    }

    @Override
    public void onBackPressed() {
        // do not allow back to dismiss while ringing
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
    }
}