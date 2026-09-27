package ohi.andre.consolelauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;

/**
 * Minimal foreground service whose only job is to keep the app process alive
 * while music plays, so the OS does not kill it when the screen goes off or
 * the user switches to another app. It shows a silent, persistent notification
 * (required by Android for any foreground service) and holds no player itself —
 * the MediaPlayer stays in MusicPlayerActivity.
 *
 * No playback controls, no media-style actions, no gray — just a black/green
 * placeholder notification.
 */
public class MusicPlaybackService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int    NOTIF_ID   = 0xA11CE;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep alive. If we get killed, restart.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_play_circle)
                .setContentTitle("Music Player")
                .setContentText("Playing in background")
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFF00FF00);
        }
        return b.build();
    }
}