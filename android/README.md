# New Star Android App

This repository automatically builds a test-installable Android APK from the latest `main` branch.

## Download the latest APK

1. Open the repository on GitHub.
2. Open **Actions**.
3. Select **Android App (automatic APK)**.
4. Open the newest successful run (green check mark).
5. Under **Artifacts**, download `new-star-android-apk-...`.
6. Extract the ZIP and install `app-debug.apk` on an Android test device.

**Do not use Code -> Download ZIP as the Android installer.** That ZIP contains source code, not an APK.

Every push to `main` starts a fresh Android build. The artifact is tied to the exact Git commit and includes a SHA-256 checksum. Failed builds stay visible in Actions with their error logs, making it easier to identify which code change caused a regression. Artifacts are retained for 30 days.

## Important build and security notes

- The automatic APK is a **debug/test build**, signed with Android's standard debug key. It is intended for controlled testing, not public distribution or Play Store release.
- Android or Play Protect may still show a warning for a sideloaded or unfamiliar app. A successful signature check does not mean Google has certified the app as safe.
- Do not disable Android security protections to install it.
- A public release requires a securely managed, persistent upload key and a signed release build. Never commit a keystore or share its passwords in chat.
- The Android launcher and all five primary navigation destinations (Home, Upload, Inbox, Gaming and Profile) now use native Jetpack Compose screens. The legacy WebView activity has been removed from the manifest and source tree; the Vercel site is not loaded as the app's main screen.
- Native authentication supports Supabase email/password and a Google OAuth PKCE handoff back into the Android app. Session tokens are stored encrypted with Android Keystore and refreshed when required.
- Native Upload uses Android's document picker, reads video metadata on-device, and uploads resumably through the existing authenticated Cloudflare TUS proxy before inserting the video record in Supabase.
- Native Profile currently displays the signed-in creator's profile and video list. Native Inbox currently lists messages and supports sending a text message by recipient user ID; full conversation discovery, realtime updates, media messages, follow-request controls and profile editing are not yet at web-feature parity.
- Native Gaming LIVE prepares a Cloudflare live input, requests Android MediaProjection permission and starts the existing WebRTC foreground service. The current service publishes screen video and an optional microphone track; game/system playback audio mixing, face-camera overlay and guest-seat publishing are not implemented in this native path.
- Real-device testing is required for login, navigation, uploads, permissions, network behavior and streaming before public release.
- Google Play publishing also requires store listing assets, privacy policy, Data safety declarations, content rating, target API compliance and Play Console review.
