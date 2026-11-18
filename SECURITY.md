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
