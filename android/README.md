# New Star Android App

## Download and install the Android app

Open the [latest New Star Android release](https://github.com/rkdaifilms/new-star/releases/latest) on an Android phone, download **NewStar.apk**, then tap the downloaded file and confirm Android's installer prompt.

- The APK is built and signed by GitHub Actions from a version tag.
- The release also includes **NewStar.aab** for Google Play publishing and **SHA256SUMS.txt** to verify downloads.
- Android always controls installation and may ask the user to confirm installation from the browser or file manager used to download the APK. Do not disable Play Protect or other device security checks.
- Updating an installed app requires the same persistent signing key and a higher Android versionCode. Never publish a replacement APK signed with a different key as an update.

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

A successful GitHub build proves that the APK compiles and is signed; it does not prove every phone, camera, microphone, upload, or streaming feature works. Test on supported Android devices before public distribution. The app uses a WebView wrapper, so browser/WebView limitations and Android permission prompts still apply.
