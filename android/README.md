# New Star Android App

The Android project is being migrated to a native application. The launcher no longer loads the New Star Vercel website.

## Current native implementation

- Native Android email/password sign-in and account creation through Supabase Auth.
- Native Google account selection through Android Credential Manager, exchanging the Google ID token with Supabase.
- Native feed data loading from the New Star Supabase API.
- Secure video URL resolution through the existing video-url Edge Function.
- Native video playback surface and creator username search.
- Native creator account screen, creator profile details, published-video list, upload entry point, and sign-out.
- Native video upload to Cloudflare Stream using the TUS upload flow, with progress reporting.
- Native video likes, comments, creator search, and follow/unfollow actions.
- Existing Android MediaProjection gaming service remains in the project, but it is not yet wired into a complete native LIVE experience.

## Build and test APK

Every change under `android/**` on `main` or `new-star-gift-ui-fix` starts the Android APK workflow.

1. Open the repository's **Actions** tab.
2. Select **New Star Creator Android APK**.
3. Open the newest successful run.
4. Download the APK artifact and extract `app-debug.apk`.

The APK is a debug build for controlled device testing. It is not a Play Store release.

## Important implementation status

This is an incremental native migration, not a claim that every New Star feature is already native. A full native creator profile editor, full video editor, messaging/inbox, LIVE hosting and viewing, LIVE gaming room controls and verified game/mic/team audio mix, battles, gifts/coins/wallet, monetization, notifications, and New Star Creator Studio AI still require native implementation and device verification. The native Google sign-in, upload, feed actions, and profile screens also require real-device end-to-end verification. The app uses Supabase and Cloudflare services as backend APIs; it does not require the Vercel website to render its native screens.

Do not publish this build as a complete product until the remaining features and real-device tests pass.
