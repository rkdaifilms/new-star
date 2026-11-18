# Cloudflare Stream integration (New Star)

## Current rollout state

- The Cloudflare TUS proxy source lives at `supabase/functions/cloudflare-tus-proxy/index.ts`.
- It is not deployed or enabled until the Cloudflare credentials are configured as Supabase Edge Function secrets and the upload client is updated.
- Existing Supabase Storage videos remain supported during migration.
- Do not put Cloudflare API tokens or signing keys in browser code, GitHub, or chat.

## Required Supabase Edge Function secrets

Set these through the Supabase project dashboard's Edge Function secrets interface:

- `CLOUDFLARE_ACCOUNT_ID`: Cloudflare account identifier.
- `CLOUDFLARE_STREAM_TOKEN`: API token with the minimum required Stream write permission.

Supabase provides `SUPABASE_URL` and `SUPABASE_ANON_KEY` to Edge Functions. Never use the service-role key for this upload proxy.

## Upload contract

The browser must use `tus-js-client` with the endpoint:

`https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/cloudflare-tus-proxy`

The initial TUS request must include:
- The user's Supabase access token in `Authorization: Bearer ...`
- The public Supabase `apikey` header
- `Tus-Resumable: 1.0.0`
- `Upload-Length` with the selected file's byte size
- `Upload-Metadata` including `maxDurationSeconds` encoded as base64

Use a TUS chunk size of at least 5 MiB. Cloudflare requires TUS for files over 200 MB. The proxy caps files at 5 GiB and duration at 3 hours. These are application-side limits, not a claim that the account's available storage/billing capacity has been confirmed.

## Safe rollout sequence

1. Confirm Cloudflare Stream is enabled on the intended Cloudflare account and that the account has sufficient video storage capacity.
2. Add the two Cloudflare secrets above in Supabase.
3. Deploy `cloudflare-tus-proxy` with JWT verification enabled.
4. Update `camera.html` to request a TUS upload session through the proxy, upload directly to Cloudflare, and save the returned Stream UID/provider metadata in the existing `videos` row.
5. Add a server-side playback-token path for private Stream videos. Never expose the Cloudflare API token or signing key to the browser.
6. Update the feed and profile player to support Cloudflare Stream HLS playback while preserving the existing Supabase Storage path for older videos.
7. Test with a small owned/licensed video first, then a 200+ MB file, interrupted/resumed upload, processing completion, playback permissions, and deletion.
8. Only after successful end-to-end tests should the UI advertise the 5 GiB limit.

## Important

A successful GitHub commit or Vercel page response does not prove Cloudflare upload or playback works. Verify the actual TUS upload, Stream processing state, signed playback, and database record before announcing the feature as live.
