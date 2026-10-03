package ohi.andre.consolelauncher.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "ohi.andre.consolelauncher.alarm.FIRE";
    public static final String EXTRA_ID = "alarm_id";

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
            context.startActivity(ring);

            // re-arm repeat or auto-disable one-shot
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
            if (!AlarmStore.hasActive(context)) AlarmService.stop(context);
        }
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

    private static long nextTrigger(AlarmModel m) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, m.hour);
        c.set(Calendar.MINUTE, m.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        if (m.repeats()) {
            // find next matching day
            for (int i = 0; i < 8; i++) {
                int day = c.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sun
                boolean match = false;
                for (int d : m.repeatDays) if (d == day) { match = true; break; }
                if (match && c.getTimeInMillis() > now.getTimeInMillis()) {
                    return c.getTimeInMillis();
                }
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
            // fallback one day later
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