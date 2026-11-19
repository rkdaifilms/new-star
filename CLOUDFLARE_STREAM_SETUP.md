# Cloudflare Stream integration (New Star)

## Current rollout state

- The Cloudflare TUS proxy source lives at `supabase/functions/cloudflare-tus-proxy/index.ts`.
- `cloudflare-tus-proxy` is deployed as Supabase Edge Function version 4 with JWT verification enabled. It returns HTTP 503 until both Cloudflare secrets are configured.
- `video-url` is deployed as Supabase Edge Function version 11. It checks Cloudflare processing status, marks ready videos as published, and returns short-lived signed HLS playback URLs.
- `cloudflare-live` is deployed as Supabase Edge Function version 1 with JWT verification enabled. It creates/reuses an authenticated creator's Cloudflare Live Input, stores room metadata, and returns the private WHIP broadcast URL only to that creator.
- `camera.html` now sends uploads through the TUS proxy and saves Cloudflare provider metadata. The client-side target cap is 5 GiB and duration cap is 3 hours; real uploads are not verified until secrets/account capacity are configured.
- `index.html` and `profile.html` load HLS.js for adaptive playback and retain the old Supabase Storage path.
- `live.html` connects the creator camera/microphone to Cloudflare Stream using browser-native WebRTC/WHIP, and marks the `live_rooms` row active only after the WHIP handshake succeeds. It closes the WHIP session and stops local media when the creator ends the broadcast.
- `index.html` now queries public active `live_rooms` rows and connects viewers to `stream_playback_url` using browser-native WHEP. It shows a LIVE list, room title, creator name when profile access permits, and a Watch/Stop viewing flow. The viewer sends receive-only audio/video transceivers and deletes the WHEP session when stopped or leaving the Live tab.
- End-to-end playback has not yet been exercised because the database currently has zero active LIVE rooms. Validate by starting a real broadcast from a separate signed-in creator account and watching it from a second browser/device.
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
3. The proxy is already deployed with JWT verification enabled; after adding secrets, confirm it no longer returns the configuration 503.
4. `camera.html` is updated to request a TUS upload session through the proxy, upload directly to Cloudflare, and save the returned Stream UID/provider metadata in the existing `videos` row with `processing_status=processing`.
5. Server-side playback-token creation and processing-state checks are implemented in `video-url`. Never expose the Cloudflare API token or signing key to the browser.
6. Live broadcast uses `cloudflare-live` to create a Live Input and browser-native WHIP. The WHIP URL is a stream credential and must only be returned to the authenticated creator. The WHEP playback URL is stored with the room for viewer integration.
7. Viewer playback uses the public WHEP playback URL stored on an active `live_rooms` row. Never expose the WHIP broadcast URL to viewers or store it in the database.
6. Feed and profile HLS.js playback support is implemented while preserving the existing Supabase Storage path for older videos.
7. Test with a small owned/licensed video first, then a 200+ MB file, interrupted/resumed upload, processing completion, playback permissions, and deletion.
8. Only after successful end-to-end tests should the UI advertise the 5 GiB limit.

## Important

A successful GitHub commit or Vercel page response does not prove Cloudflare upload or playback works. Verify the actual TUS upload, Stream processing state, signed playback, and database record before announcing the feature as live.
