package uk.joeuk89.radioalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Android calls this at alarm time. It hands over to AlarmService, which plays the radio. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (AlarmScheduler.ACTION_CANCEL_SNOOZE.equals(action)) {
            AlarmScheduler.cancelSnooze(context);
            AlarmService.cancelSnoozedNotification(context);
            return;
        }

        boolean isMain = AlarmScheduler.ACTION_FIRE.equals(action);
        boolean isSnooze = AlarmScheduler.ACTION_FIRE_SNOOZE.equals(action);
        boolean isTest = AlarmScheduler.ACTION_FIRE_TEST.equals(action);
        boolean isBadTest = AlarmScheduler.ACTION_FIRE_TEST_BAD.equals(action);
        if (!isMain && !isSnooze && !isTest && !isBadTest) return;

        if (isSnooze) {
            AlarmStore.setSnoozeUntil(context, 0);
            AlarmService.cancelSnoozedNotification(context);
        }
        // Register the next occurrence straight away, before anything else can go wrong.
        AlarmScheduler.sync(context);

        Intent service = new Intent(context, AlarmService.class)
            .setAction(AlarmService.ACTION_START)
            .putExtra(AlarmService.EXTRA_BAD_STREAM, isBadTest);
        ContextCompat.startForegroundService(context, service);
    }
}
