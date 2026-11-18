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
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** The bridge between the web UI and the native alarm code. */
@CapacitorPlugin(name = "Alarm")
public class AlarmPlugin extends Plugin {

    // Test-play in the station picker. Uses the same player as the alarm, at normal media volume.
    private ExoPlayer preview;
    private volatile String previewUrl = "";
    private volatile String previewStatus = "idle"; // idle, loading, playing, error

    @Override
    public void load() {
        // Opening the app re-registers the alarms, as a safety net.
        AlarmScheduler.sync(getContext());
    }

    @Override
    protected void handleOnPause() {
        stopPreview();
    }

    @Override
    protected void handleOnDestroy() {
        stopPreview();
    }

    // ---- Alarms ---------------------------------------------------------------------------

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void saveAlarm(PluginCall call) {
        JSObject json = call.getObject("alarm");
        if (json == null) {
            call.reject("No alarm given");
            return;
        }
        AlarmStore.Alarm alarm = AlarmStore.upsert(getContext(), AlarmStore.Alarm.fromJson(json));
        boolean scheduled = AlarmScheduler.sync(getContext());
        JSObject result = state();
        result.put("scheduled", scheduled);
        result.put("savedId", alarm.id);
        call.resolve(result);
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Context context = getContext();
        AlarmStore.Alarm alarm = AlarmStore.find(context, call.getInt("id", -1));
        if (alarm != null) {
            alarm.enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
            AlarmStore.upsert(context, alarm);
        }
        boolean scheduled = AlarmScheduler.sync(context);
        JSObject result = state();
        result.put("scheduled", scheduled);
        call.resolve(result);
    }

    @PluginMethod
    public void deleteAlarm(PluginCall call) {
        Context context = getContext();
        int id = call.getInt("id", -1);
        AlarmScheduler.cancelAlarm(context, id);
        AlarmStore.delete(context, id);
        AlarmScheduler.sync(context);
        call.resolve(state());
    }

    @PluginMethod
    public void testAlarm(PluginCall call) {
        JSObject json = call.getObject("alarm");
        AlarmStore.Alarm alarm = json != null ? AlarmStore.Alarm.fromJson(json) : new AlarmStore.Alarm();
        int seconds = AlarmStore.clamp(call.getInt("seconds", 60), 5, 3600);
        boolean badStream = Boolean.TRUE.equals(call.getBoolean("badStream", false));
        boolean scheduled = AlarmScheduler.scheduleTest(getContext(), alarm, seconds, badStream);
        JSObject result = new JSObject();
        result.put("scheduled", scheduled);
        result.put("at", System.currentTimeMillis() + seconds * 1000L);
        call.resolve(result);
    }

    /** "Preview alarm now": rings immediately with the given settings, on the real alarm screen. */
    @PluginMethod
    public void previewAlarm(PluginCall call) {
        JSObject json = call.getObject("alarm");
        if (json == null) {
            call.reject("No alarm given");
            return;
        }
        stopPreview();
        Context context = getContext();
        if (!AlarmService.ringing) {
            Intent service = new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_START)
                .putExtra(AlarmService.EXTRA_ALARM, json.toString())
                .putExtra(AlarmService.EXTRA_IN_APP, true);
            ContextCompat.startForegroundService(context, service);
        }
        getActivity().startActivity(new Intent(context, AlarmActivity.class));
        call.resolve();
    }

    /** Stops the alarm only if it is a preview started from the app. */
    @PluginMethod
    public void stopAlarmPreview(PluginCall call) {
        if (AlarmService.ringing && AlarmService.inAppPreview) {
            getContext().startService(new Intent(getContext(), AlarmService.class)
                .setAction(AlarmService.ACTION_STOP));
        }
        call.resolve();
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

    // ---- Favourite stations -----------------------------------------------------------------

    @PluginMethod
    public void setFavourites(PluginCall call) {
        JSArray favourites = call.getArray("favourites");
        if (favourites != null) AlarmStore.setFavourites(getContext(), favourites.toString());
        call.resolve();
    }

    // ---- Test-play --------------------------------------------------------------------------

    @PluginMethod
    public void previewStart(PluginCall call) {
        String url = call.getString("url", "");
        boolean hls = Boolean.TRUE.equals(call.getBoolean("hls", false));
        if (url == null || url.isEmpty() || AlarmService.ringing) {
            call.resolve(previewState());
            return;
        }
        previewUrl = url;
        previewStatus = "loading";
        getActivity().runOnUiThread(() -> {
            releasePreview();
            try {
                DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("RadioAlarm/1.0 (Android)")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(8_000);
                preview = new ExoPlayer.Builder(getContext())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(getContext())
                        .setDataSourceFactory(new DefaultDataSource.Factory(getContext(), http)))
                    .build();
                preview.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true);
                MediaItem.Builder item = new MediaItem.Builder().setUri(url);
                if (hls || url.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
                preview.setMediaItem(item.build());
                preview.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying) previewStatus = "playing";
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        previewStatus = "error";
                    }
                });
                preview.setPlayWhenReady(true);
                preview.prepare();
            } catch (RuntimeException e) {
                previewStatus = "error";
            }
        });
        call.resolve(previewState());
    }

    @PluginMethod
    public void previewStop(PluginCall call) {
        stopPreview();
        call.resolve(previewState());
    }

    @PluginMethod
    public void previewState(PluginCall call) {
        call.resolve(previewState());
    }

    private JSObject previewState() {
        JSObject o = new JSObject();
        o.put("status", previewStatus);
        o.put("url", previewUrl);
        return o;
    }

    private void stopPreview() {
        previewStatus = "idle";
        previewUrl = "";
        if (getActivity() != null) getActivity().runOnUiThread(this::releasePreview);
    }

    private void releasePreview() {
        if (preview != null) {
            preview.release();
            preview = null;
        }
    }

    // ---- Reliability check ------------------------------------------------------------------

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
                    AlarmStore.setFlag(context, "askedNotifications", true);
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

    // ---- State sent to the web UI -----------------------------------------------------------

    private JSObject state() {
        Context context = getContext();
        long now = System.currentTimeMillis();

        List<AlarmStore.Alarm> alarms = AlarmStore.loadAll(context);
        JSONArray list = new JSONArray();
        long soonest = 0;
        for (AlarmStore.Alarm alarm : alarms) {
            JSONObject o = alarm.toJson();
            long next = alarm.enabled ? AlarmScheduler.nextTrigger(alarm, now) : 0;
            try {
                o.put("next", next);
            } catch (JSONException ignored) {
            }
            if (next > 0 && (soonest == 0 || next < soonest)) soonest = next;
            list.put(o);
        }

        JSONArray favourites;
        try {
            favourites = new JSONArray(AlarmStore.getFavourites(context));
        } catch (JSONException e) {
            favourites = new JSONArray();
        }

        long snoozeUntil = AlarmStore.getSnoozeUntil(context);

        JSObject checks = new JSObject();
        checks.put("exactAlarms", AlarmScheduler.canScheduleExact(context));
        checks.put("notifications", NotificationManagerCompat.from(context).areNotificationsEnabled());
        checks.put("fullScreen", canUseFullScreen(context));
        PowerManager power = context.getSystemService(PowerManager.class);
        checks.put("battery", power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()));

        JSObject result = new JSObject();
        result.put("alarms", list);
        result.put("favourites", favourites);
        result.put("nextTrigger", soonest);
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
}
