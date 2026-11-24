# New Star AI Engine

This is a protected New Star frontend workspace on branch `new-star-ai-engine-foundation`. Provider credentials stay on the server; the browser never receives API keys.

## Files
- `new-star-ai.html` — AI workspace
- `new-star-ai.css` — responsive styling
- `new-star-ai.js` — authenticated gateway calls, voice input/output, image rendering
- `navbar.js` — authenticated navigation entry
- `supabase/functions/ai-gateway/index.ts` — synced source for the existing Supabase AI gateway

## Connected capabilities
The page uses the existing Supabase Edge Function `ai-gateway` with the signed-in user's session.

- **Chat:** sends conversation history and language; receives the provider's actual answer.
- **Voice input:** records in the browser and calls `action: "transcribe"`; transcript is inserted into the composer for review/edit before sending.
- **Voice output:** calls `action: "speak"` for actual audio and plays the returned audio.
- **Image generation:** calls `action: "image"`, sends the selected 1K/2K/4K size, displays returned base64 image data, and provides a save link. Upload-based image editing is not connected yet.

These actions require sign-in, server-side provider secrets, and available provider quota. The gateway applies per-user rate limiting. A configured key is not proof that a provider request will succeed; provider errors are surfaced rather than replaced with fake output.

## Not connected yet
- **Live Research:** needs a real search provider and source retrieval.
- **Video generation:** needs a video inference provider, job queue, storage, and job-status polling.
- **Image editing from an uploaded reference:** not implemented in the gateway/page yet.

Keep these modes marked unavailable until their real services are integrated.

## Gateway deployment
The existing Supabase project `New Star` has an active `ai-gateway` Edge Function. Version 74 was deployed to add the New Star AI branch preview origin to its CORS allowlist while preserving its existing authentication and provider logic. The same source was committed in this branch.

## Vercel deployment status
The frontend changes are committed to GitHub branch `new-star-ai-engine-foundation`. A fresh Vercel deployment was blocked by the Vercel API's HTTP 402 `api-deployments-free-per-day` daily limit. Therefore the existing Vercel preview is older and does not contain these latest frontend commits. Do not treat the latest GitHub source as already deployed to Vercel.

## Safety and security
- Never put provider keys or service-role keys in frontend code.
- Keep Supabase session validation and per-user rate limiting on the Edge Function.
- Do not claim a result was generated unless the backend returned the actual result.
- Do not claim Research or Video works until those backends are deployed and tested.
