package ohi.andre.consolelauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Minimal foreground service whose only job is to keep the app process alive
 * while music plays, so the OS does not kill it when the screen goes off or
 * the user switches to another app.
 *
 * It shows a silent, low-priority, persistent notification — required by
 * Android for any foreground service on API 26+ and mandatory on API 34+ for
 * the mediaPlayback type.
 */
public class MusicPlaybackService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int    NOTIF_ID   = 0xA11CE;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Restart if the OS kills us.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundInternal() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            // API 29+ allows specifying the foreground service type.
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

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
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play_circle)
                .setContentTitle("Music Player")
                .setContentText("Playing in background")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setColor(0xFF00FF00);
        return b.build();
    }
}