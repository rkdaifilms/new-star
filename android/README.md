# New Star Android App

The Android project is being migrated to a native application. The launcher no longer loads the New Star Vercel website.

## Current native implementation

- Native Android sign-in and account creation through Supabase Auth.
- Native feed data loading from the New Star Supabase API.
- Secure video URL resolution through the existing video-url Edge Function.
- Native video playback surface and creator username search.
- Native account screen and sign-out.
- Existing Android MediaProjection gaming service remains in the project, but it is not yet wired into a complete native LIVE experience.

## Build and test APK

Every change under `android/**` on `main` or `new-star-gift-ui-fix` starts the Android APK workflow.

1. Open the repository's **Actions** tab.
2. Select **New Star Creator Android APK**.
3. Open the newest successful run.
4. Download the APK artifact and extract `app-debug.apk`.

The APK is a debug build for controlled device testing. It is not a Play Store release.

## Important implementation status

This is an incremental native migration, not a claim that every New Star feature is already native. Native Google sign-in, upload/editor, profile editing, comments/likes/follows, messaging, LIVE hosting and viewing, gaming audio mix, gifts/wallet, monetization, notifications, and New Star AI still require native implementation and device verification. The app uses Supabase and Cloudflare services as backend APIs; it does not require the Vercel website to render its native screens.

Do not publish this build as a complete product until the remaining features and real-device tests pass.
