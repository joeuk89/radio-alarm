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

        String alarmJson = null;
        boolean badStream = false;

        if (AlarmScheduler.ACTION_FIRE.equals(action)) {
            AlarmStore.Alarm alarm = AlarmStore.find(context, intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1));
            if (alarm != null && alarm.enabled) {
                alarmJson = alarm.toJson().toString();
                if (!alarm.repeats()) {
                    // A one-off alarm switches itself off once it has rung.
                    alarm.enabled = false;
                    AlarmStore.upsert(context, alarm);
                }
            }
        } else if (AlarmScheduler.ACTION_FIRE_SNOOZE.equals(action)) {
            alarmJson = AlarmStore.getString(context, AlarmScheduler.KEY_SNOOZE_ALARM);
            AlarmStore.setSnoozeUntil(context, 0);
            AlarmService.cancelSnoozedNotification(context);
        } else if (AlarmScheduler.ACTION_FIRE_TEST.equals(action)) {
            alarmJson = AlarmStore.getString(context, AlarmScheduler.KEY_TEST_ALARM);
            badStream = AlarmStore.getFlag(context, AlarmScheduler.KEY_TEST_BAD);
        } else {
            return;
        }

        // Register the next occurrences straight away, before anything else can go wrong.
        AlarmScheduler.sync(context);
        if (alarmJson == null) return;

        Intent service = new Intent(context, AlarmService.class)
            .setAction(AlarmService.ACTION_START)
            .putExtra(AlarmService.EXTRA_ALARM, alarmJson)
            .putExtra(AlarmService.EXTRA_BAD_STREAM, badStream);
        ContextCompat.startForegroundService(context, service);
    }
}
