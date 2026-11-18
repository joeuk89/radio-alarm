package uk.joeuk89.radioalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The ringing alarm. Runs as a foreground service so Android keeps it alive with the screen
 * off. Plays the station with ExoPlayer (which handles the BBC's HLS streams), raises the
 * volume gradually, and switches to a bundled tone if the station is not playing.
 */
public class AlarmService extends Service {

    static final String ACTION_START = "uk.joeuk89.radioalarm.START";
    static final String ACTION_STOP = "uk.joeuk89.radioalarm.STOP";
    static final String ACTION_SNOOZE = "uk.joeuk89.radioalarm.SNOOZE";
    static final String EXTRA_BAD_STREAM = "badStream";
    static final String EXTRA_ALARM = "alarm";
    /** Set when the alarm is started from the app's "Preview alarm now" button. */
    static final String EXTRA_IN_APP = "inApp";

    private static final String CHANNEL_RINGING = "alarm_ringing";
    private static final String CHANNEL_STATUS = "alarm_status";
    private static final int NOTIFICATION_RINGING = 1;
    private static final int NOTIFICATION_SNOOZED = 2;

    /** No sound from the station for this long means the backup tone starts. */
    private static final long FALLBACK_AFTER_MS = 10_000;
    /** While on the backup tone, keep trying the station this often. */
    private static final long RETRY_EVERY_MS = 20_000;
    /** An alarm nobody answers stops by itself after an hour. */
    private static final long MAX_RING_MS = 60 * 60_000L;
    private static final long TICK_MS = 250;
    /** The backup tone never plays quieter than this, however low the ramp starts. */
    private static final int BACKUP_MIN_PERCENT = 15;

    // Read by the lock-screen alarm screen and the web UI.
    public static volatile boolean ringing = false;
    public static volatile boolean inAppPreview = false;
    public static volatile boolean usingBackup = false;
    public static volatile boolean everPlayed = false;
    public static volatile int volumePercent = 0;
    public static volatile int startPercent = 0;
    public static volatile int endPercent = 0;
    public static volatile int snoozeMinutes = 9;
    public static volatile String stationName = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;

    private AlarmStore.Alarm alarm;
    private ExoPlayer player;
    private MediaPlayer tone;
    private Ringtone lastResort;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private long startedAt;
    private long lastGoodAt;
    private long lastRetryAt;
    private int savedStreamVolume = -1;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            if (!ringing) {
                AlarmStore.Alarm requested = AlarmStore.Alarm.fromJsonString(intent.getStringExtra(EXTRA_ALARM));
                startRinging(requested != null ? requested : new AlarmStore.Alarm(),
                    intent.getBooleanExtra(EXTRA_BAD_STREAM, false),
                    intent.getBooleanExtra(EXTRA_IN_APP, false));
            } else {
                // Already ringing (e.g. a test fired during an alarm): just keep going.
                showRingingNotification();
            }
        } else if (ACTION_SNOOZE.equals(action) && ringing) {
            snooze();
        } else if (ACTION_STOP.equals(action) && ringing) {
            stopRinging();
        } else if (!ringing) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (ringing) cleanUp();
        super.onDestroy();
    }

    // ---- Starting -------------------------------------------------------------------------

    private void startRinging(AlarmStore.Alarm which, boolean badStream, boolean inApp) {
        alarm = which;
        inAppPreview = inApp;
        ringing = true;
        usingBackup = false;
        everPlayed = false;
        stationName = alarm.stationName;
        startPercent = alarm.startVolume;
        endPercent = alarm.endVolume;
        volumePercent = alarm.startVolume;
        snoozeMinutes = alarm.snoozeMinutes;

        // Must happen within a few seconds of the service starting.
        showRingingNotification();

        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioAlarm:ringing");
        wakeLock.acquire(MAX_RING_MS + 60_000);

        audio = getSystemService(AudioManager.class);
        takeOverAlarmVolume();
        requestAudioFocus();

        startedAt = SystemClock.elapsedRealtime();
        lastGoodAt = startedAt;
        lastRetryAt = startedAt;

        String url = badStream ? "https://stream.invalid/unreachable.m3u8" : alarm.stationUrl;
        try {
            startStream(url);
        } catch (RuntimeException e) {
            startBackupTone();
        }
        handler.post(tick);
    }

    /**
     * The alarm ignores the phone's alarm-volume slider: we set that slider to full and control
     * loudness ourselves, then put the slider back afterwards.
     */
    private void takeOverAlarmVolume() {
        int leftover = AlarmStore.getSavedVolume(this);
        savedStreamVolume = leftover >= 0 ? leftover : audio.getStreamVolume(AudioManager.STREAM_ALARM);
        AlarmStore.setSavedVolume(this, savedStreamVolume);
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (SecurityException ignored) {
            // Leave the slider where it is; the alarm still plays.
        }
    }

    private void requestAudioFocus() {
        android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { /* an alarm keeps playing regardless */ })
            .build();
        audio.requestAudioFocus(focusRequest);
    }

    private void startStream(String url) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent("RadioAlarm/0.2 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000);

        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(new DefaultDataSource.Factory(this, http)))
            .build();

        // "Alarm" usage: plays on the alarm channel, so silent mode and media volume don't affect it.
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_ALARM)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build(), false);
        player.setWakeMode(C.WAKE_MODE_NETWORK);

        AudioDeviceInfo speaker = builtInSpeaker();
        if (speaker != null) player.setPreferredAudioDevice(speaker);

        player.setVolume(gainFor(alarm.startVolume));

        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (alarm.stationHls || url.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                // ExoPlayer has already retried by itself; don't leave the room silent.
                startBackupTone();
            }
        });
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /** Play through the phone's own speaker even when headphones or a Bluetooth speaker are connected. */
    @Nullable
    private AudioDeviceInfo builtInSpeaker() {
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }

    // ---- While ringing --------------------------------------------------------------------

    private void onTick() {
        if (!ringing) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - startedAt;

        float progress = alarm.rampSeconds <= 0 ? 1f : Math.min(1f, elapsed / (alarm.rampSeconds * 1000f));
        float percent = alarm.startVolume + (alarm.endVolume - alarm.startVolume) * progress;
        volumePercent = Math.round(percent);

        boolean streamPlaying = false;
        if (player != null) {
            player.setVolume(gainFor(percent));
            streamPlaying = player.isPlaying();
        }
        if (tone != null) {
            float g = gainFor(Math.max(percent, BACKUP_MIN_PERCENT));
            try {
                tone.setVolume(g, g);
            } catch (IllegalStateException ignored) {
            }
        }

        if (streamPlaying) {
            lastGoodAt = now;
            everPlayed = true;
            if (usingBackup) stopBackupTone();
        } else if (!usingBackup && now - lastGoodAt > FALLBACK_AFTER_MS) {
            startBackupTone();
        }

        if (usingBackup && player != null && now - lastRetryAt > RETRY_EVERY_MS) {
            lastRetryAt = now;
            if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        }

        if (elapsed > MAX_RING_MS) {
            stopRinging();
            return;
        }
        handler.postDelayed(tick, TICK_MS);
    }

    /**
     * Turns a volume percentage into playback gain. The curve is steep at the bottom so that
     * low settings are genuinely faint (1% is silent, 5% is barely there, 60% is clearly loud).
     */
    private static float gainFor(float percent) {
        float p = Math.max(0f, Math.min(100f, percent)) / 100f;
        return (float) Math.pow(p, 2.5);
    }

    private void startBackupTone() {
        if (!ringing || usingBackup) return;
        usingBackup = true;
        lastRetryAt = SystemClock.elapsedRealtime();
        try {
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            tone = MediaPlayer.create(this, R.raw.backup_tone, attributes, audio.generateAudioSessionId());
            if (tone != null) {
                tone.setLooping(true);
                AudioDeviceInfo speaker = builtInSpeaker();
                if (speaker != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tone.setPreferredDevice(speaker);
                }
                float g = gainFor(Math.max(volumePercent, BACKUP_MIN_PERCENT));
                tone.setVolume(g, g);
                tone.start();
            }
        } catch (RuntimeException e) {
            tone = null;
        }
        if (tone == null) {
            // Even the bundled tone failed: fall back to the phone's own alarm sound.
            try {
                lastResort = RingtoneManager.getRingtone(this, Settings.System.DEFAULT_ALARM_ALERT_URI);
                if (lastResort != null) {
                    lastResort.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
                    lastResort.play();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopBackupTone() {
        usingBackup = false;
        if (tone != null) {
            try {
                tone.stop();
            } catch (IllegalStateException ignored) {
            }
            tone.release();
            tone = null;
        }
        if (lastResort != null) {
            lastResort.stop();
            lastResort = null;
        }
    }

    // ---- Ending ---------------------------------------------------------------------------

    private void snooze() {
        cleanUp();
        long until = AlarmScheduler.scheduleSnooze(this, alarm);
        showSnoozedNotification(until);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopRinging() {
        cleanUp();
        AlarmScheduler.cancelSnooze(this);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanUp() {
        ringing = false;
        inAppPreview = false;
        handler.removeCallbacks(tick);
        stopBackupTone();
        if (player != null) {
            player.release();
            player = null;
        }
        if (audio != null) {
            if (savedStreamVolume >= 0) {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, savedStreamVolume, 0);
                } catch (SecurityException ignored) {
                }
                AlarmStore.setSavedVolume(this, -1);
                savedStreamVolume = -1;
            }
            if (focusRequest != null) {
                audio.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    // ---- Notifications --------------------------------------------------------------------

    private static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel ringingChannel = new NotificationChannel(
            CHANNEL_RINGING, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH);
        ringingChannel.setDescription("Shows the alarm with snooze and stop while it is ringing");
        ringingChannel.setSound(null, null);
        ringingChannel.enableVibration(false);
        ringingChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ringingChannel);

        NotificationChannel statusChannel = new NotificationChannel(
            CHANNEL_STATUS, "Snoozed alarm", NotificationManager.IMPORTANCE_LOW);
        statusChannel.setDescription("Shows when a snoozed alarm will ring again");
        nm.createNotificationChannel(statusChannel);
    }

    private PendingIntent servicePendingIntent(String action, int code) {
        Intent intent = new Intent(this, AlarmService.class).setAction(action);
        return PendingIntent.getService(this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void showRingingNotification() {
        ensureChannels(this);

        Intent fullScreen = new Intent(this, AlarmActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullScreenIntent = PendingIntent.getActivity(this, 20, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (inAppPreview) {
            // The app already has the alarm screen open, so a quiet notification is enough.
            Notification quiet = new NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("Alarm preview")
                .setContentText(stationName)
                .setOngoing(true)
                .setContentIntent(fullScreenIntent)
                .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 22))
                .build();
            ServiceCompat.startForeground(this, NOTIFICATION_RINGING, quiet,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
            return;
        }

        String time = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date());
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarm · " + time)
            .setContentText(stationName)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenIntent)
            // This is what puts the alarm screen over the lock screen.
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, "Snooze", servicePendingIntent(ACTION_SNOOZE, 21))
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 22))
            .build();

        ServiceCompat.startForeground(this, NOTIFICATION_RINGING, notification,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
    }

    private void showSnoozedNotification(long until) {
        ensureChannels(this);
        Intent cancel = new Intent(this, AlarmReceiver.class).setAction(AlarmScheduler.ACTION_CANCEL_SNOOZE);
        PendingIntent cancelIntent = PendingIntent.getBroadcast(this, 23, cancel,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String time = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(until));
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Snoozed until " + time)
            .setContentText(stationName)
            .setOngoing(true)
            .setContentIntent(AlarmScheduler.showPendingIntent(this))
            .addAction(0, "Cancel snooze", cancelIntent)
            .build();
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_SNOOZED, notification);
        } catch (SecurityException ignored) {
        }
    }

    static void cancelSnoozedNotification(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_SNOOZED);
    }
}
