# New Star Android Gaming Capture

This is the native Android companion for the Gaming LIVE page.

## What it currently does
- Opens the existing New Star Gaming LIVE page inside a controlled WebView.
- Exposes a native bridge only to the New Star production origin.
- Requests Android's system MediaProjection consent for each screen-share session.
- Runs capture in a foreground service with the Android 14+ mediaProjection service type.
- Publishes the captured screen video to the authenticated room's Cloudflare Stream WHIP URL.
- Waits for ICE gathering and a real WebRTC connected state before the website confirms the room as LIVE.
- Deletes the WHIP resource and stops capture when the creator ends the stream.

## Build
Open the `android/` directory in Android Studio with JDK 17 and Android SDK 35, or run:

```sh
gradle -p android :app:assembleDebug
```

GitHub Actions also runs the Android debug build when files under `android/` change and uploads the APK as an Actions artifact if the build passes.

## Important current limits
- The initial native publisher sends screen video only. Game/system audio, microphone mixing, face-camera overlay, and the interactive Calls guest-seat publisher are not yet part of this native path.
- Android can deny capture of protected/secure app surfaces. Some apps also opt out of playback audio capture.
- The APK must be built, installed, signed as appropriate, and tested on a real Android device before native Gaming LIVE can be called operational.
- The normal website in Chrome does not receive the native bridge; use the Android companion app for this capture path.
- The WHIP URL is fetched for the authenticated creator at runtime and is not stored in the repository.

Do not mark a room LIVE until the native service reports a connected WebRTC media session.
