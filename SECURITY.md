# New Star Creator Hub — Security Notes

## Browser API-key rule
- Never put OpenAI, Gemini, OpenRouter, payment-provider, Payoneer, AdMob secret, or Supabase service-role keys in HTML, JavaScript, public configuration, or Git history.
- The Supabase publishable/anon key is designed to be public. It is not an authorization boundary; enforce access with Row Level Security and server-side authorization.
- AI provider calls must go through an authenticated Supabase Edge Function (or another trusted server). Store provider keys only as server-side function secrets. Do not call provider APIs directly from browser code with a secret.
- A browser cannot be made to ignore CORS, network failures, provider rate limits, content blockers, or user-installed extensions. Do not promise that an API will always work. Use server-side retries with bounded timeouts, clear error states, and provider fallback only where configured.

## Netlify response headers
The `_headers` file applies baseline browser security headers. It intentionally does not set a restrictive Content-Security-Policy yet because this static app loads several third-party SDKs and uses camera, microphone, screen sharing, OAuth, and realtime connections. A CSP must be built from an audited allowlist or it can break core features.

## Before production launch
1. Configure secrets in Supabase Edge Function secrets; never commit them.
2. Verify every exposed table has least-privilege grants and RLS policies.
3. Verify SECURITY DEFINER functions use a fixed search_path and explicit authorization checks.
4. Configure Supabase Auth leaked-password protection and appropriate rate limits in the dashboard.
5. Test OAuth, uploads, camera/microphone/screen capture, video playback, follow requests, realtime, wallet and payment webhooks on the deployed domain. Static JavaScript syntax checks do not replace browser/end-to-end tests.
6. Treat provider downtime as a recoverable error. Never credit coins or creator earnings based on a client-side success message.

## Security hardening applied to the connected Supabase project
- Enabled RLS on all 26 tables in the `public` schema and all 16 tables in the `private` schema.
- Browser roles cannot access the `private` schema directly.
- Removed browser-role `TRUNCATE`, `TRIGGER`, and `REFERENCES` privileges from public tables; removed anonymous table mutations and only retained authenticated mutations where an explicit RLS policy exists.
- Removed browser access to public-schema sequences.
- Tightened video row visibility to published public videos for the public feed, while owners can still see their own rows. Private-account videos are visible only to approved followers under the row policy.
- The `videos` Storage bucket is private. Owner-only storage policies protect uploads and direct object reads; the `video-url` Edge Function issues short-lived signed playback URLs after checking publication, visibility, audience, account privacy, and approved-follow status.
- Comments and likes are readable/writable only in relation to videos visible to that viewer; private account discovery cards omit full name, avatar URL, and bio while the account is private.
- Follow requests are participant-scoped; only the target account can accept/reject a pending request, and acceptance creates the follower relationship through a database trigger.
- The secure gift database RPC is service-role-only and checks fixed gift price, idempotency key, video publication/visibility, private-account approval, wallet balance, and commission before writing ledgers. No deployed gift Edge Function or verified app caller currently exists; the LIVE gift catalogue remains preview-only and sends no gifts.
- Legacy merged-video publishing now requires an authenticated owner, uploads into that owner's storage folder, stores a `storage://videos/` reference, and cleans up the uploaded object if the metadata insert fails.
- Wallet balances, wallet ledger, payment orders, messages, notifications, blocks, and withdrawal requests retain owner/participant-based policies.
- Enabled RLS on all private-schema tables. Private tables intentionally have no browser policies, so browser access is denied; server-side service-role code remains the trusted access path.
- Avatar uploads are limited to 5 MiB and JPEG, PNG, WebP, or AVIF.

## Remaining production blockers — do not mark security complete yet
- Supabase Auth leaked-password protection is still disabled and must be enabled in the Supabase Auth dashboard by the authorized project owner.
- Seven authenticated RPC functions remain `SECURITY DEFINER` for watch sessions, feed ranking, and creator eligibility. Their authorization logic and fixed `search_path` need continued review; do not blindly revoke them without replacing their app calls.
- Default privileges for future `public` objects were tightened for the `postgres` owner. The available database role could not alter `supabase_admin` default privileges, so that role's defaults remain unverified.
- The Security Advisor still reports 18 RLS-enabled tables with no policies (private-schema tables intentionally deny browser access; `public.app_settings` and `public.star_campaign_events` need separate review) and seven authenticated SECURITY DEFINER RPC warnings.
- Avatar objects remain in a public bucket for current profile-avatar URL compatibility. Private-account discovery metadata is masked, but an already-known public avatar URL is not revoked by that masking.
- A restrictive Content-Security-Policy and full deployed-domain regression testing remain outstanding.
- GitHub changes do not prove that Netlify has deployed them. Verify the live deployment before treating browser changes as active.

Security is layered risk reduction, not a promise that the app can never be copied, attacked, or taken offline.
