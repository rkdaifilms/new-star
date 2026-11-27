# New Star Android App

The creator Android app is a native Jetpack Compose application. APK workflows are manual and are intentionally not started for every small code change.

## Test APK workflow

A test APK can be built only after the Android implementation is ready for a test cycle:

1. Open the repository on GitHub and select **Actions**.
2. Select **Android App (automatic APK)** or **New Star Creator Android APK**.
3. Use **Run workflow** on the intended branch.
4. Open the successful run and download its artifact ZIP.
5. Extract the ZIP and install `app-debug.apk` on an Android test device.

**Do not use Code -> Download ZIP as the Android installer.** That ZIP contains source code, not an APK.

The debug APK is signed with Android's standard debug key and is for controlled testing, not public distribution. The separate signed release workflow requires a configured persistent upload keystore. No APK is produced merely by committing source changes.

## Important build and security notes

- The automatic APK is a **debug/test build**, signed with Android's standard debug key. It is intended for controlled testing, not public distribution or Play Store release.
- Android or Play Protect may still show a warning for a sideloaded or unfamiliar app. A successful signature check does not mean Google has certified the app as safe.
- Do not disable Android security protections to install it.
- A public release requires a securely managed, persistent upload key and a signed release build. Never commit a keystore or share its passwords in chat.
- The creator app launcher and its Home, Upload, Inbox, Live, Gaming, Wallet and Profile routes use native Jetpack Compose screens. The creator app does not load the Vercel site as its main screen. The separate `developer-android` project still uses a WebView for `admin.html`; it is not yet a fully native developer app.
- Native authentication supports Supabase email/password and a Google OAuth PKCE handoff back into the Android app. Session tokens are stored encrypted with Android Keystore and refreshed when required.
- Native Upload uses Android's document picker, reads video metadata on-device, and uploads resumably through the existing authenticated Cloudflare TUS proxy before inserting the video record in Supabase.
- Native Profile currently displays the signed-in creator's profile and video list. Native Inbox currently lists messages and supports sending a text message by recipient user ID; full conversation discovery, realtime updates, media messages, follow-request controls and profile editing are not yet at web-feature parity.
- Native Gaming LIVE prepares a Cloudflare live input, requests Android MediaProjection permission and starts the existing WebRTC foreground service. The current service publishes screen video and an optional microphone track; game/system playback audio mixing, face-camera overlay and guest-seat publishing are not implemented in this native path.
- Real-device testing is required for login, navigation, uploads, permissions, network behavior and streaming before public release.
- Google Play publishing also requires store listing assets, privacy policy, Data safety declarations, content rating, target API compliance and Play Console review.
