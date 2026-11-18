package uk.joeuk89.radioalarm;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** The bridge between the web UI and the native alarm code. */
@CapacitorPlugin(name = "Alarm")
public class AlarmPlugin extends Plugin {

    @Override
    public void load() {
        // Opening the app re-registers the alarm, as a safety net.
        AlarmScheduler.sync(getContext());
    }

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void setAlarm(PluginCall call) {
        Context context = getContext();
        AlarmStore.Alarm alarm = AlarmStore.load(context);
        alarm.hour = clamp(call.getInt("hour", alarm.hour), 0, 23);
        alarm.minute = clamp(call.getInt("minute", alarm.minute), 0, 59);
        alarm.enabled = Boolean.TRUE.equals(call.getBoolean("enabled", alarm.enabled));
        AlarmStore.save(context, alarm);
        boolean scheduled = AlarmScheduler.sync(context);
        JSObject result = state();
        result.put("scheduled", scheduled);
        call.resolve(result);
    }

    @PluginMethod
    public void testAlarm(PluginCall call) {
        int seconds = clamp(call.getInt("seconds", 60), 5, 3600);
        boolean badStream = Boolean.TRUE.equals(call.getBoolean("badStream", false));
        boolean scheduled = AlarmScheduler.scheduleTest(getContext(), seconds, badStream);
        JSObject result = new JSObject();
        result.put("scheduled", scheduled);
        result.put("at", System.currentTimeMillis() + seconds * 1000L);
        call.resolve(result);
    }

    @PluginMethod
    public void cancelSnooze(PluginCall call) {
        AlarmScheduler.cancelSnooze(getContext());
        AlarmService.cancelSnoozedNotification(getContext());
        call.resolve(state());
    }

    @PluginMethod
    public void stopRinging(PluginCall call) {
        if (AlarmService.ringing) {
            getContext().startService(new Intent(getContext(), AlarmService.class)
                .setAction(AlarmService.ACTION_STOP));
        }
        call.resolve();
    }

    /** Opens the Android screen (or prompt) that fixes one item on the reliability check. */
    @PluginMethod
    public void fix(PluginCall call) {
        Context context = getContext();
        String which = call.getString("which", "");
        Uri pkg = Uri.parse("package:" + context.getPackageName());
        Intent intent = null;

        switch (which == null ? "" : which) {
            case "exactAlarms":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg);
                }
                break;
            case "notifications":
                boolean canPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    && !AlarmStore.getFlag(context, "askedNotifications");
                if (canPrompt) {
                    AlarmStore.setFlag(context, "askedNotifications");
                    ActivityCompat.requestPermissions(getActivity(),
                        new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7001);
                    call.resolve();
                    return;
                }
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                break;
            case "fullScreen":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
                }
                break;
            case "battery":
                intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
                break;
            default:
                break;
        }

        if (intent == null) {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        }
        try {
            getActivity().startActivity(intent);
        } catch (RuntimeException e) {
            try {
                getActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
            } catch (RuntimeException ignored) {
            }
        }
        call.resolve();
    }

    private JSObject state() {
        Context context = getContext();
        AlarmStore.Alarm alarm = AlarmStore.load(context);
        long now = System.currentTimeMillis();

        JSObject a = new JSObject();
        a.put("hour", alarm.hour);
        a.put("minute", alarm.minute);
        a.put("enabled", alarm.enabled);
        a.put("stationName", alarm.stationName);
        a.put("startVolume", alarm.startVolume);
        a.put("endVolume", alarm.endVolume);
        a.put("rampSeconds", alarm.rampSeconds);
        a.put("snoozeMinutes", alarm.snoozeMinutes);

        long snoozeUntil = AlarmStore.getSnoozeUntil(context);

        JSObject checks = new JSObject();
        checks.put("exactAlarms", AlarmScheduler.canScheduleExact(context));
        checks.put("notifications", NotificationManagerCompat.from(context).areNotificationsEnabled());
        checks.put("fullScreen", canUseFullScreen(context));
        PowerManager power = context.getSystemService(PowerManager.class);
        checks.put("battery", power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()));

        JSObject result = new JSObject();
        result.put("alarm", a);
        result.put("nextTrigger", alarm.enabled ? AlarmScheduler.nextTrigger(alarm, now) : 0L);
        result.put("snoozeUntil", snoozeUntil > now ? snoozeUntil : 0L);
        result.put("ringing", AlarmService.ringing);
        result.put("checks", checks);
        return result;
    }

    private static boolean canUseFullScreen(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    private static int clamp(Integer value, int min, int max) {
        int v = value == null ? min : value;
        return Math.max(min, Math.min(max, v));
    }
}
