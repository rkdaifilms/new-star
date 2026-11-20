# New Star Android App

The Android wrapper opens the complete New Star web app at the production root URL. The native screen-capture bridge is restricted to the New Star HTTPS origin and is used by Gaming LIVE when running inside this Android app.

## Debug build
The debug workflow creates a test APK. A successful build does not certify the APK as Play Store-ready or prove device streaming works.

## Signed release build
Google Play publishing uses a signed Android App Bundle (AAB). The repository deliberately does not contain a private signing key.

1. Create and securely back up an Android upload keystore on a trusted computer. Never commit it to GitHub or send it in chat.
2. In GitHub repository Settings -> Secrets and variables -> Actions, add:
   - `ANDROID_KEYSTORE_BASE64`: base64-encoded keystore file
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. Run the **Android Release (AAB + APK)** workflow manually from Actions.
4. Download the `new-star-android-release` artifact. Use the AAB for Play Console internal testing/release and the APK for controlled device testing.

The upload key must be backed up securely. Losing it can complicate future app updates. Do not share passwords or keystore files in chat.

## Current limitations / release gates
- Gaming LIVE native publishing currently sends screen video only; game/system audio, microphone mixing, face-camera overlay and Calls guest-seat publishing are not implemented in this native path.
- Real device testing is required for login, navigation, screen capture, network behavior, and streaming before public release.
- Google Play publication also requires store listing assets, privacy policy, Data safety declarations, content rating, target API compliance, and Play Console review.
- The debug APK is not the final Play Store release.
