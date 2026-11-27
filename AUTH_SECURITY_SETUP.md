# New Star Authentication and Production Security Setup

This document records the exact hosted-dashboard settings that cannot be safely enabled through the database migration interface. Do not put provider secrets in HTML, JavaScript, GitHub, chat, or public database rows.

## Supabase project

- Project ref: `uzsusgoetrkujmofxcmb`
- Production site currently recorded by the project owner: `https://new-star-nepal.netlify.app`
- Supabase region: `ap-south-1`

## Required dashboard configuration

Open the New Star project in Supabase Dashboard.

### 1. Authentication → URL Configuration

- Set Site URL to `https://new-star-nepal.netlify.app`.
- Add the production callback URLs actually used by the app to Redirect URLs.
- Add local development callback URLs only if local development is still used.
- Do not add broad wildcard domains that are not controlled by the project owner.
- Confirm Google OAuth callback URL in the Google Cloud OAuth client exactly matches the callback URL shown by Supabase.

### 2. Authentication → Providers

- Enable only sign-in providers that are configured and tested.
- For Google OAuth, configure the OAuth Client ID and Client Secret in Supabase's provider settings. Keep the client secret server-side.
- For phone OTP, configure a supported SMS provider and verify delivery, expiry, rate limits, and country coverage before enabling it for production.
- Do not show a provider as active in the app until a real sign-in test succeeds.

### 3. Authentication → Security and Protection

- Enable leaked-password protection if the project plan exposes the control. Supabase documents this feature as available on Pro plan and above.
- Review minimum password length and character requirements before enforcing stronger rules; test existing-user sign-in and password reset first.
- Configure CAPTCHA only after the matching frontend widget and server-side secret validation are implemented. A dashboard toggle alone is not a complete CAPTCHA integration.
- Keep MFA available for sensitive account changes where supported.

### 4. Authentication → SMTP Settings

- Configure a verified production SMTP provider and sender domain.
- Test signup confirmation, email OTP/magic link if used, password reset, and change-email confirmation.
- Verify SPF, DKIM, and DMARC for the sender domain.
- Do not rely on the built-in trial email sender for production delivery.

### 5. Storage and deployment

- Keep private media buckets private and serve protected media through short-lived signed URLs.
- Verify CORS and allowed origins for the production site only.
- Configure production secrets in Supabase Edge Function secrets / hosting environment, never in client-side files.
- After each deployment, verify the actual production domain and test signup, login, logout, recovery, and protected page access.

## Security state verified in the database on 2026-11-18

- The 14 missing foreign-key indexes are present.
- Public RLS policies no longer contain direct per-row `auth.uid()` calls; the relevant expressions use scalar subqueries.
- The two overlapping campaign SELECT-policy pairs have been consolidated without widening access.
- Seven authenticated SECURITY DEFINER RPCs now have explicit grants to `authenticated` and `service_role`, and no execution grant to `anon` or `PUBLIC`. Their RPC access is intentional because the function bodies enforce authenticated-user checks; the Supabase linter still flags SECURITY DEFINER RPCs for review.
- The 18 RLS-enabled tables without policies reported by the advisor are in the `private` schema or deliberately fail-closed public tables. Do not add permissive policies merely to silence this informational warning.
- The two duplicate permissive-policy performance warnings are cleared.

## Do not mark production-ready until these checks pass

1. Real email confirmation and password recovery are delivered through production SMTP.
2. Every enabled OAuth/SMS provider completes a real end-to-end test.
3. CAPTCHA is enabled only after frontend and backend validation are both present.
4. Leaked-password protection is enabled where plan support permits.
5. RLS tests confirm a user cannot read or modify another user's private rows.
6. Production site deployment is verified separately from GitHub commits.


## Native Android Google Sign-In

The native Android app uses Android Credential Manager with Google ID tokens and exchanges the token with Supabase Auth. This presents the Google account chooser from accounts already added to the phone; the user can select the account they want.

Required configuration before Google sign-in can complete:

1. In Google Cloud Console, create an OAuth 2.0 **Web application** client ID. Add the Supabase Auth callback URL shown in Authentication → Providers → Google as an authorized redirect URI.
2. Configure that Web client ID and its client secret in Supabase Authentication → Providers → Google. Keep the client secret only in Supabase.
3. Add the Web client ID (the public client ID ending in `.apps.googleusercontent.com`) as the GitHub Actions repository variable `GOOGLE_WEB_CLIENT_ID`. Do not add a client secret to GitHub or the Android app.
4. Confirm the Android package name is `com.newstar.live`; register the app's actual signing SHA-1/SHA-256 fingerprints in the matching Google Cloud Android OAuth client if required by the Google project setup.
5. Build a fresh APK and test account selection and a successful Supabase session on a real Android device.

The Google button remains visible in the native login screen. If the Web client ID is not configured at build time, the app displays a configuration message instead of pretending that sign-in is ready.
