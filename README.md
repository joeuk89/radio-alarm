# Radio Alarm

A personal Android alarm clock that wakes you gently to live internet radio.

- Web UI (plain HTML/CSS/JS in `www/`) wrapped with Capacitor.
- Alarm scheduling, playback and the lock-screen alarm are native Android code in `android/` (from stage 2).
- Sideloaded, not published to the Play Store.

## Getting the app

Every push to `main` builds an APK with GitHub Actions and publishes it as the latest release.

1. On the phone, open this link in Chrome to download the newest build:
   https://github.com/joeuk89/radio-alarm/releases/latest/download/radio-alarm.apk
2. Wait for the download to finish (Chrome may warn that the file type can be harmful: choose **Download anyway**).
3. Open the download. The first time, Android asks you to allow Chrome to install unknown apps: allow it, go back, and tap **Install**.
4. Later builds install straight over the old one and keep your settings. If Play Protect asks about an unrecognised app, choose **Install anyway**.

The build number is shown at the bottom of the app's main screen and matches the release name (`Build N`).

## What it does

- Any number of alarms, each with its own time, repeat days, station, volume ramp and snooze length. An alarm with no days chosen rings once and switches itself off.
- Station search through the community [Radio Browser](https://www.radio-browser.info/) directory, favourites, and test-play before choosing.
- Gentle wake: volume rises from a start level to an end level over a set time.
- A backup chime if the station fails outright or has produced no sound for 10 seconds. While the chime plays, the app retries the station every 20 seconds.
- Snooze and Stop on a native screen over the lock screen.
- A reliability check (the sliders icon, top right) for the Android permissions an alarm needs.

## How the alarm works

- `AlarmStore` keeps alarms and favourites on the native side, in storage that can be read before the phone is first unlocked after a restart.
- `AlarmScheduler` registers each alarm with `AlarmManager.setAlarmClock()`, the same mechanism the built-in Clock app uses.
- `AlarmReceiver` is called by Android at alarm time and starts `AlarmService`.
- `AlarmService` is a foreground service. It plays the station with Media3 ExoPlayer (including HLS, which BBC streams use), ramps the volume, and falls back to `res/raw/backup_tone.ogg`.
- `AlarmActivity` is the native screen shown over the lock screen.
- `BootReceiver` re-registers alarms after a restart, app update, or clock change.
- `AlarmPlugin` connects the web UI in `www/` to all of the above, and runs test-play through the same player the alarm uses.

Volume: the alarm plays on the phone's alarm channel through the phone speaker, so silent mode, media volume and connected Bluetooth devices do not affect it. While ringing it sets the alarm-volume slider to full and controls loudness itself, then puts the slider back. The percentage scale is steep at the bottom: 1% is silent, 5% is barely there, 60% is clearly loud.

## Testing checklist

1. **Your existing alarm survived.** After updating, the alarm you had is still listed, now as "Every day".
2. **Add an alarm.** Tap Add alarm, set a time, choose days, Save. It appears in the list, switched on, and "Next alarm in…" updates.
3. **Edit and delete.** Tap an alarm to edit it; Delete alarm removes it.
4. **Search.** In the editor tap Station, search for a station, tap play to test it, tap the star to keep it, tap its name to choose it.
5. **Quiet start.** Set Start volume to 1%, tap "Preview alarm now". The alarm screen opens straight away; it should begin inaudibly and rise.
6. **Two alarms.** Set two alarms a few minutes apart with different stations; each should ring with its own station.
7. **One-off alarm.** Deselect every day, save, let it ring, stop it. It should now be switched off in the list.
8. **Backup tone and tests.** Sliders icon, top right: both test buttons still work as before.
9. **Restart.** With an alarm set a few minutes ahead, restart the phone and leave it locked. It should still ring.

## Working on it

```
npm ci
npx cap sync android     # copies www/ into the Android project
cd android && ./gradlew assembleDebug
```

Builds are signed with a private key that is not in the repo. GitHub Actions restores it from two repository secrets, `SIDELOAD_KEYSTORE_B64` (the keystore, base64-encoded) and `SIDELOAD_KEYSTORE_PASSWORD`, so every build has the same signature and installs over the previous one. If those secrets are ever replaced with a different key, uninstall the app before installing the next build.
