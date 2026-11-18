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

## Stages

| Stage | What it adds | Status |
|---|---|---|
| 1 | Repo, app shell, icon, APK build | Done |
| 2 | One BBC Radio 4 alarm: native scheduling, volume ramp, lock-screen UI, snooze/stop, backup tone, reboot survival, reliability check | Built, being tested |
| 3 | Alarm list and editor | Next |
| 4 | Station search, favourites, test-play | |
| 5 | Polish | |

## How the alarm works

- `AlarmScheduler` registers the alarm with `AlarmManager.setAlarmClock()`, the same mechanism the built-in Clock app uses.
- `AlarmReceiver` is called by Android at alarm time and starts `AlarmService`.
- `AlarmService` is a foreground service. It plays the station with Media3 ExoPlayer (HLS), ramps the volume, and switches to the bundled tone (`res/raw/backup_tone.ogg`) if the station fails outright or has produced no sound for 10 seconds. While on the tone it retries the station every 20 seconds.
- `AlarmActivity` is the native screen shown over the lock screen, with Snooze and Stop.
- `BootReceiver` re-registers the alarm after a restart, app update, or clock change.
- `AlarmPlugin` connects the web UI to all of the above.

Volume: the alarm plays on the phone's alarm channel through the phone speaker. While ringing it sets the alarm-volume slider to full and controls loudness itself (5% rising to 60% over 3 minutes in this stage), then puts the slider back.

## Testing stage 2

Install the newest build over the old one, open the app, then:

1. **Reliability check.** Tap **Fix** on every row that has one, until all four rows show a tick. Notifications shows a prompt; Battery asks whether the app may always run in the background (choose Allow).
2. **Test alarm, phone locked.** Tap **Test alarm in 1 minute**, lock the phone, put it down. Expect: the screen lights up with the alarm screen, Radio 4 starts very quietly and rises over 3 minutes, and the status line reads "Live · volume rising".
3. **Snooze.** Tap Snooze. Expect: silence, a "Snoozed until" notification, and the alarm again 9 minutes later. To skip the wait, tap **Cancel snooze** in the notification or the app.
4. **Stop.** Run another test and tap Stop. Expect: silence, and the phone's own alarm volume slider back where it was.
5. **Backup tone.** Tap **Test backup tone in 1 minute** and lock the phone. This uses a station address that cannot work. Expect: a soft chime within a second or two and the status "Backup tone · station unavailable".
6. **No internet.** Turn on aeroplane mode, tap **Test alarm in 1 minute**, lock the phone. Expect: the chime within about 10 seconds. Turn aeroplane mode off while it rings: the radio should take over within about 20 seconds.
7. **Phone in use.** Run a test with the phone unlocked. Expect: a pop-up notification with Snooze and Stop rather than the full screen.
8. **Restart.** Set the real alarm a few minutes ahead and switch it on, restart the phone, and do not unlock it. Expect: it still rings.
9. **Overnight.** Set it for tomorrow morning and use it for real, with another alarm as a safety net the first night.

## Working on it

```
npm ci
npx cap sync android     # copies www/ into the Android project
cd android && ./gradlew assembleDebug
```

Builds are signed with a private key that is not in the repo. GitHub Actions restores it from two repository secrets, `SIDELOAD_KEYSTORE_B64` (the keystore, base64-encoded) and `SIDELOAD_KEYSTORE_PASSWORD`, so every build has the same signature and installs over the previous one. If those secrets are ever replaced with a different key, uninstall the app before installing the next build.
