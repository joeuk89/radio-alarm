package uk.joeuk89.radioalarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/**
 * Registers alarms with Android using AlarmManager.setAlarmClock(), the mechanism the
 * built-in Clock app uses: it fires on time even when the phone is in deep sleep.
 */
public final class AlarmScheduler {

    static final String ACTION_FIRE = "uk.joeuk89.radioalarm.FIRE";
    static final String ACTION_FIRE_SNOOZE = "uk.joeuk89.radioalarm.FIRE_SNOOZE";
    static final String ACTION_FIRE_TEST = "uk.joeuk89.radioalarm.FIRE_TEST";
    static final String ACTION_CANCEL_SNOOZE = "uk.joeuk89.radioalarm.CANCEL_SNOOZE";
    static final String EXTRA_ALARM_ID = "alarmId";

    static final String KEY_SNOOZE_ALARM = "snoozeAlarm";
    static final String KEY_TEST_ALARM = "testAlarm";
    static final String KEY_TEST_BAD = "testBad";

    private static final int CODE_SNOOZE = 2;
    private static final int CODE_TEST = 3;
    private static final int CODE_SHOW = 10;
    private static final int CODE_ALARM_BASE = 1000;

    private AlarmScheduler() {}

    /** The next time (ms) this alarm should ring, strictly after "now". 0 if it never will. */
    public static long nextTrigger(AlarmStore.Alarm alarm, long now) {
        boolean repeats = alarm.repeats();
        for (int offset = 0; offset <= 7; offset++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.add(Calendar.DAY_OF_YEAR, offset);
            c.set(Calendar.HOUR_OF_DAY, alarm.hour);
            c.set(Calendar.MINUTE, alarm.minute);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= now) continue;
            // Calendar counts Sunday as 1; our list starts on Monday.
            int day = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if (!repeats || alarm.days[day]) return c.getTimeInMillis();
        }
        return 0;
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = context.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    /**
     * Makes Android's registered alarms match what is saved. Safe to call any time:
     * after a change, after a reboot, after an app update, or when the app opens.
     */
    public static boolean sync(Context context) {
        long now = System.currentTimeMillis();
        boolean ok = true;

        List<AlarmStore.Alarm> alarms = AlarmStore.loadAll(context);
        for (AlarmStore.Alarm alarm : alarms) {
            PendingIntent operation = alarmPendingIntent(context, alarm.id);
            long at = alarm.enabled ? nextTrigger(alarm, now) : 0;
            if (at > 0) {
                ok &= setExact(context, at, operation);
            } else {
                cancel(context, operation);
            }
        }

        long snoozeUntil = AlarmStore.getSnoozeUntil(context);
        PendingIntent snooze = simplePendingIntent(context, ACTION_FIRE_SNOOZE, CODE_SNOOZE);
        if (snoozeUntil > now) {
            ok &= setExact(context, snoozeUntil, snooze);
        } else {
            if (snoozeUntil != 0) AlarmStore.setSnoozeUntil(context, 0);
            cancel(context, snooze);
        }
        return ok;
    }

    /** Call when an alarm is deleted, so Android forgets it too. */
    public static void cancelAlarm(Context context, int id) {
        cancel(context, alarmPendingIntent(context, id));
    }

    /** Rings the given alarm again in the given number of minutes. Returns the time it will ring. */
    public static long scheduleSnooze(Context context, AlarmStore.Alarm alarm) {
        long at = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L;
        AlarmStore.setString(context, KEY_SNOOZE_ALARM, alarm.toJson().toString());
        AlarmStore.setSnoozeUntil(context, at);
        setExact(context, at, simplePendingIntent(context, ACTION_FIRE_SNOOZE, CODE_SNOOZE));
        return at;
    }

    public static void cancelSnooze(Context context) {
        AlarmStore.setSnoozeUntil(context, 0);
        cancel(context, simplePendingIntent(context, ACTION_FIRE_SNOOZE, CODE_SNOOZE));
    }

    /** A one-off test of the given settings. "badStream" uses an address that cannot work, to prove the backup tone. */
    public static boolean scheduleTest(Context context, AlarmStore.Alarm alarm, int seconds, boolean badStream) {
        long at = System.currentTimeMillis() + seconds * 1000L;
        AlarmStore.setString(context, KEY_TEST_ALARM, alarm.toJson().toString());
        AlarmStore.setFlag(context, KEY_TEST_BAD, badStream);
        return setExact(context, at, simplePendingIntent(context, ACTION_FIRE_TEST, CODE_TEST));
    }

    private static boolean setExact(Context context, long at, PendingIntent operation) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return false;
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPendingIntent(context)), operation);
            return true;
        } catch (SecurityException e) {
            // Exact alarms are not allowed yet; the reliability check tells the user how to fix it.
            return false;
        }
    }

    private static void cancel(Context context, PendingIntent operation) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(operation);
    }

    private static PendingIntent alarmPendingIntent(Context context, int id) {
        Intent intent = new Intent(context, AlarmReceiver.class)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM_ID, id);
        return PendingIntent.getBroadcast(context, CODE_ALARM_BASE + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent simplePendingIntent(Context context, String action, int code) {
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** What opens if the user taps the alarm icon in the status bar. */
    static PendingIntent showPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, CODE_SHOW, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
