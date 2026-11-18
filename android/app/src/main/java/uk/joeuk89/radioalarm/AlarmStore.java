package uk.joeuk89.radioalarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Where alarms and favourite stations are saved. They live on the native side, in storage
 * that is readable before the phone is first unlocked after a restart, so alarms never
 * depend on the web UI.
 */
public final class AlarmStore {

    /** BBC live streams (HLS). BBC changes these addresses now and then; the backup tone covers that. */
    public static final String RADIO_4_URL =
        "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio=96000.norewind.m3u8";
    public static final String WORLD_SERVICE_URL =
        "https://as-hls-ww-live.akamaized.net/pool_87948813/live/ww/bbc_world_service/bbc_world_service.isml/bbc_world_service-audio=96000.norewind.m3u8";

    private static final String PREFS = "alarm_store";
    private static final String KEY_ALARMS = "alarms";
    private static final String KEY_FAVOURITES = "favourites";

    public static final class Alarm {
        public int id = 0;
        public int hour = 7;
        public int minute = 0;
        public boolean enabled = true;
        /** Monday first. All false means "ring once, then switch off". */
        public boolean[] days = new boolean[7];
        public String stationName = "BBC Radio 4";
        public String stationUrl = RADIO_4_URL;
        public boolean stationHls = true;
        /** Volumes are a percentage of full alarm volume. */
        public int startVolume = 5;
        public int endVolume = 60;
        public int rampSeconds = 180;
        public int snoozeMinutes = 9;

        public boolean repeats() {
            for (boolean d : days) if (d) return true;
            return false;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("hour", hour);
                o.put("minute", minute);
                o.put("enabled", enabled);
                JSONArray d = new JSONArray();
                for (boolean day : days) d.put(day);
                o.put("days", d);
                o.put("stationName", stationName);
                o.put("stationUrl", stationUrl);
                o.put("stationHls", stationHls);
                o.put("startVolume", startVolume);
                o.put("endVolume", endVolume);
                o.put("rampSeconds", rampSeconds);
                o.put("snoozeMinutes", snoozeMinutes);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static Alarm fromJson(JSONObject o) {
            Alarm a = new Alarm();
            if (o == null) return a;
            a.id = o.optInt("id", 0);
            a.hour = clamp(o.optInt("hour", a.hour), 0, 23);
            a.minute = clamp(o.optInt("minute", a.minute), 0, 59);
            a.enabled = o.optBoolean("enabled", true);
            JSONArray d = o.optJSONArray("days");
            if (d != null) {
                for (int i = 0; i < 7 && i < d.length(); i++) a.days[i] = d.optBoolean(i, false);
            }
            a.stationName = o.optString("stationName", a.stationName);
            a.stationUrl = o.optString("stationUrl", a.stationUrl);
            a.stationHls = o.optBoolean("stationHls", a.stationUrl.contains(".m3u8"));
            a.startVolume = clamp(o.optInt("startVolume", a.startVolume), 0, 100);
            a.endVolume = clamp(o.optInt("endVolume", a.endVolume), 0, 100);
            a.rampSeconds = clamp(o.optInt("rampSeconds", a.rampSeconds), 0, 3600);
            a.snoozeMinutes = clamp(o.optInt("snoozeMinutes", a.snoozeMinutes), 1, 60);
            return a;
        }

        public static Alarm fromJsonString(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                return fromJson(new JSONObject(json));
            } catch (JSONException e) {
                return null;
            }
        }
    }

    private AlarmStore() {}

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Alarms ---------------------------------------------------------------------------

    public static List<Alarm> loadAll(Context context) {
        SharedPreferences p = prefs(context);
        List<Alarm> list = new ArrayList<>();
        String json = p.getString(KEY_ALARMS, null);
        if (json == null) {
            // First run of the multi-alarm version: carry over the single alarm from before.
            if (p.contains("hour")) {
                Alarm a = new Alarm();
                a.id = 1;
                a.hour = p.getInt("hour", 7);
                a.minute = p.getInt("minute", 0);
                a.enabled = p.getBoolean("enabled", false);
                for (int i = 0; i < 7; i++) a.days[i] = true;
                list.add(a);
            }
            saveAll(context, list);
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) list.add(Alarm.fromJson(array.optJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    public static void saveAll(Context context, List<Alarm> alarms) {
        JSONArray array = new JSONArray();
        for (Alarm a : alarms) array.put(a.toJson());
        prefs(context).edit().putString(KEY_ALARMS, array.toString()).apply();
    }

    public static Alarm find(Context context, int id) {
        for (Alarm a : loadAll(context)) if (a.id == id) return a;
        return null;
    }

    /** Adds the alarm (id 0) or replaces the one with the same id. Returns it with its id set. */
    public static Alarm upsert(Context context, Alarm alarm) {
        List<Alarm> all = loadAll(context);
        if (alarm.id <= 0) {
            int max = 0;
            for (Alarm a : all) max = Math.max(max, a.id);
            alarm.id = max + 1;
            all.add(alarm);
        } else {
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id == alarm.id) {
                    all.set(i, alarm);
                    replaced = true;
                }
            }
            if (!replaced) all.add(alarm);
        }
        saveAll(context, all);
        return alarm;
    }

    public static void delete(Context context, int id) {
        List<Alarm> all = loadAll(context);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == id) all.remove(i);
        saveAll(context, all);
    }

    // ---- Favourite stations (kept as the JSON the web UI gives us) ---------------------------

    public static String getFavourites(Context context) {
        String json = prefs(context).getString(KEY_FAVOURITES, null);
        if (json != null) return json;
        JSONArray defaults = new JSONArray();
        try {
            defaults.put(new JSONObject().put("name", "BBC Radio 4").put("url", RADIO_4_URL)
                .put("hls", true).put("detail", "United Kingdom · Talk, news"));
            defaults.put(new JSONObject().put("name", "BBC World Service").put("url", WORLD_SERVICE_URL)
                .put("hls", true).put("detail", "United Kingdom · News, talk"));
        } catch (JSONException ignored) {
        }
        return defaults.toString();
    }

    public static void setFavourites(Context context, String json) {
        prefs(context).edit().putString(KEY_FAVOURITES, json).apply();
    }

    // ---- Small values -----------------------------------------------------------------------

    public static String getString(Context context, String key) {
        return prefs(context).getString(key, null);
    }

    public static void setString(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
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

    public static void setFlag(Context context, String name, boolean value) {
        prefs(context).edit().putBoolean(name, value).apply();
    }
}
