# New Star Android App

New Star for Android is a **native Jetpack Compose application** (package `com.newstar.live`). Its app screens are rendered by Android; it does not embed the website in a WebView and does not load the Vercel website as its app interface.

## Native app and services

- Android UI, authentication screens, creator studio, video selection/upload UI, and LIVE controls are native Kotlin/Jetpack Compose.
- The app communicates directly with the Supabase project APIs for authentication, AI gateway, video upload sessions, and LIVE session setup.
- Video media is uploaded to Cloudflare Stream using resumable TUS; camera and gaming broadcasts use Android foreground services and WebRTC/WHIP.
- Vercel hosts the separate website/preview. It is not the Android app runtime or a dependency for rendering Android screens.
- Google OAuth may open the device's external browser for the provider sign-in flow; that does not turn the app UI into a website. Email/password sign-in stays in the native app.

## Download and install the Android app

Open the [latest New Star Android release](https://github.com/rkdaifilms/new-star/releases/latest), download **NewStar.apk**, then tap the downloaded file and confirm Android's installer prompt.

- The APK is built and signed by GitHub Actions from a version tag.
- The release also includes **NewStar.aab** for Google Play publishing and **SHA256SUMS.txt** to verify downloads.
- Android controls installation and may ask the user to confirm installation from the browser or file manager used to download the APK. Keep Play Protect and other device security checks enabled.
- Production updates require the same privately managed release signing key and a higher Android versionCode. CI debug APKs use a runner-generated debug key; uninstall an older debug build before installing a new CI debug APK if Android reports a signature conflict. Never commit any keystore or publish a debug key as a production signing key.

## Publishing a release

1. An authorized repository maintainer creates and pushes a version tag such as `v1.0.2`, or runs **Actions → Android Release (signed APK + Play Store AAB)** for an existing tag.
2. GitHub Actions builds the signed APK and AAB, then attaches them to that tag's GitHub Release.
3. Users install from the **latest release** link above.

The release workflow requires these GitHub Actions repository secrets, configured by the repository owner/adult maintainer:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the keystore and passwords private and backed up securely. Never commit them to the repository or paste them into chat. If these secrets are not configured, the signed release workflow will stop with a clear error; the separate debug APK workflow remains for testing only.

## Testing and device compatibility

A successful GitHub build proves that the APK compiles and is signed; it does not prove every phone, camera, microphone, upload, or streaming feature works. Test authentication, speech recognition, full video upload/playback, and LIVE playback on supported Android devices before public distribution. Gaming LIVE requires Android's screen-capture consent and a working Cloudflare Stream/WHIP configuration.
