package uk.joeuk89.radioalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android forgets registered alarms when the phone restarts. This puts them back after a
 * restart (even before the first unlock), an app update, or a clock or time-zone change.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmScheduler.sync(context);
    }
}
