package ohi.andre.consolelauncher.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import ohi.andre.consolelauncher.R;

public class AlarmService extends Service {

    private static final String CH_ID = "ohi_alarm_keepalive";
    private static final int NOTIF_ID = 9911;

    public static void start(Context c) {
        Intent i = new Intent(c, AlarmService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, AlarmService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AlarmStore.hasActive(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        Notification n = buildNotification();
        startForeground(NOTIF_ID, n);

        // re-arm any alarms that might have been lost
        AlarmReceiver.rescheduleAll(this);

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "Alarm keep-alive",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setDescription("Keeps scheduled alarms alive.");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, AlarmActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_file)
                .setContentTitle("Alarms active")
                .setContentText("Next alarm will fire on schedule.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi);
        return b.build();
    }
}