package vaddempudi.gnanesh.syntaxcli.alarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;
import java.util.List;

import vaddempudi.gnanesh.syntaxcli.R;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "vaddempudi.gnanesh.syntaxcli.alarm.FIRE";
    public static final String EXTRA_ID = "alarm_id";

    private static final String CH_RING = "ohi_alarm_ring";
    private static final int RING_NOTIF_ID = 9922;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (AlarmStore.hasActive(context)) {
                AlarmService.start(context);
                rescheduleAll(context);
            }
            return;
        }

        if (ACTION_FIRE.equals(action)) {
            long id = intent.getLongExtra(EXTRA_ID, -1);

            Intent ring = new Intent(context, AlarmRingActivity.class);
            ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            ring.putExtra(EXTRA_ID, id);
            try { context.startActivity(ring); } catch (Exception ignored) { }

            postAlarmNotification(context, id);

            List<AlarmModel> list = AlarmStore.load(context);
            for (AlarmModel m : list) {
                if (m.id != id) continue;
                if (m.repeats()) {
                    schedule(context, m);
                } else {
                    m.enabled = false;
                    AlarmStore.update(context, m);
                }
                break;
            }

            if (AlarmStore.hasActive(context)) AlarmService.start(context);
            else AlarmService.stop(context);
        }
    }

    private static void postAlarmNotification(Context c, long id) {
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CH_RING) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_RING, "Alarm ringing",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Shown when an alarm fires.");
                ch.enableVibration(false);
                ch.setSound(null, null);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }

        Intent ring = new Intent(c, AlarmRingActivity.class);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ring.putExtra(EXTRA_ID, id);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent fullScreen = PendingIntent.getActivity(
                c, (int) (id & 0x7fffffff), ring, piFlags);

        // Silent — the ringtone plays from AlarmRingActivity only.
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_RING)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Alarm")
                .setContentText("Tap to stop")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true);

        try { nm.notify(RING_NOTIF_ID, b.build()); } catch (SecurityException ignored) { }
    }

    public static void cancelAlarmNotification(Context c) {
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(RING_NOTIF_ID);
    }

    public static void rescheduleAll(Context c) {
        List<AlarmModel> list = AlarmStore.load(c);
        boolean anyActive = false;
        for (AlarmModel m : list) {
            if (!m.enabled) continue;
            anyActive = true;
            schedule(c, m);
        }
        if (!anyActive) AlarmService.stop(c);
    }

    public static void schedule(Context c, AlarmModel m) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(c, m);
        long trigger = nextTrigger(m);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
        }
    }

    public static void cancel(Context c, AlarmModel m) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(buildPendingIntent(c, m));
    }

    private static PendingIntent buildPendingIntent(Context c, AlarmModel m) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_ID, m.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, (int) (m.id & 0x7fffffff), i, flags);
    }

    public static long nextTrigger(AlarmModel m) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, m.hour);
        c.set(Calendar.MINUTE, m.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        if (m.repeats()) {
            for (int i = 0; i < 8; i++) {
                int day = c.get(Calendar.DAY_OF_WEEK) - 1;
                boolean match = false;
                for (int d : m.repeatDays) if (d == day) { match = true; break; }
                if (match && c.getTimeInMillis() > now.getTimeInMillis()) {
                    return c.getTimeInMillis();
                }
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
            return c.getTimeInMillis();
        } else {
            if (c.getTimeInMillis() <= now.getTimeInMillis()) {
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
            return c.getTimeInMillis();
        }
    }
}