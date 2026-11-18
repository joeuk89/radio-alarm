# Radio Alarm

A personal Android alarm clock that wakes you gently to live internet radio.

- Web UI (plain HTML/CSS/JS in `www/`) wrapped with Capacitor.
- Alarm scheduling, playback and the lock-screen alarm are native Android code in `android/` (from stage 2).
- Sideloaded, not published to the Play Store.

## Getting the app

Every push to `main` builds an APK with GitHub Actions and publishes it as the latest release.

1. On the phone, sign in to GitHub in Chrome and open
   `https://github.com/joeuk89/radio-alarm/releases/latest`
2. Under **Assets**, tap `radio-alarm.apk` to download it.
3. Open the download. The first time, Android asks you to allow Chrome to install unknown apps: allow it, go back, and tap **Install**.
4. Later builds install straight over the old one and keep your settings. If Play Protect asks about an unrecognised app, choose **Install anyway**.

The build number is shown at the bottom of the app's main screen and matches the release name (`Build N`).

## Stages

| Stage | What it adds | Status |
|---|---|---|
| 1 | Repo, app shell, icon, APK build | Done |
| 2 | One hardcoded BBC Radio 4 alarm: native scheduling, volume ramp, lock-screen UI, snooze/stop, backup tone, reboot survival, reliability check | Next |
| 3 | Alarm list and editor | |
| 4 | Station search, favourites, test-play | |
| 5 | Polish | |

## Testing stage 1

1. Install the APK as above.
2. Check the home-screen icon: a sunrise made of radio waves, amber on near-black.
3. Open the app: dark screen, large live clock, today's date, and `Build N` at the bottom.
4. Leave it open across a minute change and check the clock updates.

## Working on it

```
npm ci
npx cap sync android     # copies www/ into the Android project
cd android && ./gradlew assembleDebug
```

Builds are signed with the key in `android/app/sideload.keystore`. It is kept in the repo on purpose so every build has the same signature and can update the installed app. Keep the repo private, or replace the key with a GitHub secret before making it public.
