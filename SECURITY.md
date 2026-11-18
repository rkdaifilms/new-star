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
5. Test OAuth, uploads, camera/microphone/screen capture, video playback, realtime, wallet and payment webhooks on the deployed domain.
6. Treat provider downtime as a recoverable error. Never credit coins or creator earnings based on a client-side success message.

## Security hardening applied to the connected Supabase project
- Enabled RLS on all 26 tables in the `public` schema and all 16 tables in the `private` schema.
- Browser roles cannot access the `private` schema directly.
- Removed browser-role `TRUNCATE`, `TRIGGER`, and `REFERENCES` privileges from public tables; removed anonymous table mutations and only retained authenticated mutations where an explicit RLS policy exists.
- Removed browser access to public-schema sequences.
- Tightened video row visibility to published public/unlisted videos for everyone, while owners can still see their own rows.
- Comments and likes are now readable/writable only in relation to videos visible to that viewer.
- Wallet balances, wallet ledger, payment orders, messages, notifications, blocks, and withdrawal requests retain owner/participant-based policies.
- Enabled RLS on all private-schema tables. Private tables intentionally have no browser policies, so browser access is denied; server-side service-role code remains the trusted access path.
- Avatar uploads are limited to 5 MiB and JPEG, PNG, WebP, or AVIF.

## Remaining production blockers — do not mark security complete yet
- The `videos` Storage bucket is still public because the current app stores public video URLs and does not yet generate signed URLs. Public bucket URLs can be opened by anyone who has the URL, so private/unlisted media is not fully protected at the file layer. Migrate playback, profile grids, editor previews, and downloads to short-lived signed URLs before switching this bucket to private; switching it immediately would break existing playback.
- Supabase Auth leaked-password protection is still disabled and must be enabled in the Supabase Auth dashboard.
- Seven authenticated RPC functions remain `SECURITY DEFINER` by design for watch sessions, feed ranking, and creator eligibility. Each must retain its explicit `auth.uid()` ownership check and fixed `search_path`; do not remove these checks or blindly revoke the RPCs without replacing their app calls.
- Default privileges for future objects in the `public` schema still need to be restricted by the owning database role.
- A restrictive Content-Security-Policy and full deployed-domain regression testing remain outstanding.
- GitHub changes do not prove that Netlify has deployed them. Verify the live deployment before treating browser changes as active.

Security is layered risk reduction, not a promise that the app can never be copied, attacked, or taken offline.
