# New Star Android App

The creator Android app is a native Jetpack Compose application. APK workflows are manual and are intentionally not started for every small code change.

## Test APK workflow

Until the complete native app is finished, GitHub Actions runs compile/package/signature verification only. It deliberately does not publish an APK artifact. The final installable APK will be produced only after native feature parity, integration checks and device testing are complete.

1. Open the repository on GitHub and select **Actions**.
2. Select **Android App (automatic APK)** or **New Star Creator Android APK**.
3. Use **Run workflow** on the intended branch.
4. Open the run and confirm the compile, package and signature verification steps passed. The verification artifact contains only a checksum, not an installable APK.

**Do not use Code -> Download ZIP as the Android installer.** That ZIP contains source code, not an APK.

The debug APK is used internally by CI for compile/package/signature checks, but is not exposed as a downloadable artifact while native work is incomplete. The separate signed release workflow requires a configured persistent upload keystore. No APK is released merely by committing source changes.

## Important build and security notes

- The automatic APK is a **debug/test build**, signed with Android's standard debug key. It is intended for controlled testing, not public distribution or Play Store release.
- Android or Play Protect may still show a warning for a sideloaded or unfamiliar app. A successful signature check does not mean Google has certified the app as safe.
- Do not disable Android security protections to install it.
- A public release requires a securely managed, persistent upload key and a signed release build. Never commit a keystore or share its passwords in chat.
- The creator app launcher and its Home, Upload, Inbox, Live, Gaming, Wallet and Profile routes use native Jetpack Compose screens. The creator app does not load the Vercel site as its main screen. The separate `developer-android` project has also been converted from the `admin.html` WebView to a native Android owner console. Its current native scope is secure email/password sign-in, encrypted session storage, token refresh, server-verified owner-only dashboard metrics, financial summary, withdrawals and content-report lists. Admin management actions and the complete web admin feature set are not yet implemented in the native console.
- Native authentication supports Supabase email/password and a Google OAuth PKCE handoff back into the Android app. Session tokens are stored encrypted with Android Keystore and refreshed when required.
- Native Upload uses Android's document picker, reads video metadata on-device, and uploads resumably through the existing authenticated Cloudflare TUS proxy before inserting the video record in Supabase.
- Native Creator Studio AI now has an Android chat and image-generation screen that calls the existing authenticated `ai-gateway`; it is separate from the support-only Help Center. AI responses and image generation still require end-to-end device verification.
- Native Profile currently displays the signed-in creator's profile and video list. Native Inbox currently lists messages and supports sending a text message by recipient user ID; full conversation discovery, realtime updates, media messages, follow-request controls and profile editing are not yet at web-feature parity.
- Native Gaming LIVE prepares a Cloudflare live input, requests Android MediaProjection permission and starts the existing WebRTC foreground service. The current service publishes screen video and an optional microphone track; game/system playback audio mixing, face-camera overlay and guest-seat publishing are not implemented in this native path.
- Real-device testing is required for login, navigation, uploads, permissions, network behavior and streaming before public release.
- Google Play publishing also requires store listing assets, privacy policy, Data safety declarations, content rating, target API compliance and Play Console review.
