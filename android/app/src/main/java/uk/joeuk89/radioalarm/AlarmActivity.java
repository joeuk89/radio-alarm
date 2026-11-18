package uk.joeuk89.radioalarm;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The screen shown over the lock screen while the alarm rings. It is plain native Android,
 * not the web UI, so it cannot be held up by anything in the WebView.
 */
public class AlarmActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;

    private TextView station;
    private TextView status;
    private TextView snoozeLength;
    private ProgressBar ramp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_alarm);
        station = findViewById(R.id.alarm_station);
        status = findViewById(R.id.alarm_status);
        snoozeLength = findViewById(R.id.alarm_snooze_length);
        ramp = findViewById(R.id.alarm_ramp);

        TextView date = findViewById(R.id.alarm_date);
        date.setText(new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date()));

        findViewById(R.id.alarm_snooze).setOnClickListener(v -> send(AlarmService.ACTION_SNOOZE));
        findViewById(R.id.alarm_stop).setOnClickListener(v -> send(AlarmService.ACTION_STOP));
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    private void send(String action) {
        startService(new Intent(this, AlarmService.class).setAction(action));
        finish();
    }

    private void refresh() {
        if (!AlarmService.ringing) {
            finish();
            return;
        }
        station.setText(AlarmService.stationName);
        snoozeLength.setText(AlarmService.snoozeMinutes + " minutes");

        int start = AlarmService.startPercent;
        int end = AlarmService.endPercent;
        int now = AlarmService.volumePercent;
        boolean rising = end > start && now < end;

        if (AlarmService.usingBackup) {
            status.setText("Backup tone · station unavailable");
        } else if (!AlarmService.everPlayed) {
            status.setText("Connecting…");
        } else if (rising) {
            status.setText("Live · volume rising, " + now + "%");
        } else {
            status.setText("Live");
        }
        ramp.setProgress(end > start ? Math.round(100f * (now - start) / (end - start)) : 100);

        handler.postDelayed(refresh, 500);
    }
}
