package uk.joeuk89.radioalarm;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the alarm is saved. It lives on the native side, in storage that is readable
 * before the phone is first unlocked after a restart, so alarms never depend on the web UI.
 *
 * Stage 2 keeps a single alarm; stage 3 turns this into a list.
 */
public final class AlarmStore {

    /** BBC Radio 4 live (HLS). BBC changes these addresses now and then; the backup tone covers that. */
    public static final String RADIO_4_URL =
        "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio=96000.norewind.m3u8";

    private static final String PREFS = "alarm_store";

    public static final class Alarm {
        public int hour = 7;
        public int minute = 0;
        public boolean enabled = false;
        public String stationName = "BBC Radio 4";
        public String stationUrl = RADIO_4_URL;
        /** Volumes are a percentage of full alarm volume. */
        public int startVolume = 5;
        public int endVolume = 60;
        public int rampSeconds = 180;
        public int snoozeMinutes = 9;
    }

    private AlarmStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Alarm load(Context context) {
        SharedPreferences p = prefs(context);
        Alarm a = new Alarm();
        a.hour = p.getInt("hour", a.hour);
        a.minute = p.getInt("minute", a.minute);
        a.enabled = p.getBoolean("enabled", a.enabled);
        return a;
    }

    public static void save(Context context, Alarm a) {
        prefs(context).edit()
            .putInt("hour", a.hour)
            .putInt("minute", a.minute)
            .putBoolean("enabled", a.enabled)
            .apply();
    }

    /** Wall-clock time (ms) a snoozed alarm will ring again, or 0 if nothing is snoozed. */
    public static long getSnoozeUntil(Context context) {
        return prefs(context).getLong("snoozeUntil", 0L);
    }

    public static void setSnoozeUntil(Context context, long at) {
        prefs(context).edit().putLong("snoozeUntil", at).apply();
    }

    /** The phone's own alarm volume from before we raised it, or -1 if we are not holding one. */
    public static int getSavedVolume(Context context) {
        return prefs(context).getInt("savedVolume", -1);
    }

    public static void setSavedVolume(Context context, int volume) {
        prefs(context).edit().putInt("savedVolume", volume).apply();
    }

    public static boolean getFlag(Context context, String name) {
        return prefs(context).getBoolean(name, false);
    }

    public static void setFlag(Context context, String name) {
        prefs(context).edit().putBoolean(name, true).apply();
    }
}
