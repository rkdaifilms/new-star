# New Star AI Engine

## Current architecture

New Star has an application-owned AI orchestration layer running in Supabase. It owns authentication checks, prompts, conversation persistence, provider routing, request limits, API-key management, and model adapters. This is New Star's own AI application layer; it is **not** a foundation model trained from scratch by New Star.

- Supabase Edge Function: `ai-gateway`, active deployment version **97**.
- Frontend workspace: `new-star-ai.html`, linked from Creator Studio.
- Provider credentials remain in server-side Supabase secrets.
- Vercel hosts app previews; Supabase runs the AI backend independently of Vercel.

## Connected capabilities

- **Chat and conversation memory:** OpenAI, Gemini, and OpenRouter provider routing; conversations and messages are stored in user-owned Supabase tables. The browser remembers the conversation ID for continuation.
- **Language handling:** selected app language and latest message language guide replies. Creator AI also detects common Nepali image-generation phrasing.
- **Voice input:** browser microphone recording and gateway transcription.
- **Voice output:** gateway text-to-speech and browser audio playback.
- **Image generation:** Gemini, OpenAI, and OpenRouter image adapters. The workspace exposes 1K, 2K, and 4K choices and aspect ratios; actual support depends on the selected provider/model and its quota.
- **Image understanding:** authenticated vision action for supported image uploads.
- **Search:** New Star Search index results, with Gemini Google Search grounding as a live-web fallback when configured and available. Search results must not be represented as live web results unless grounding actually returns them.
- **Video generation:** a Google Cloud Vertex AI Gemini Omni adapter submits background jobs, checks job status, stores completed MP4 files in the private `new-star-ai-videos` bucket, and returns short-lived signed playback URLs after checking job ownership. This integration is provider-connected but not yet end-to-end verified.
- **Developer API:** signed-in users can create, list, and revoke scoped API keys. Keys are shown once and stored as SHA-256 hashes. Programmatic calls use `x-new-star-api-key` plus the Supabase publishable `apikey` header. Per-key request counters are isolated from account-level counters.

## Security and limits

- Browser requests require an allowed origin and authenticated Supabase session, or a valid scoped developer API key.
- Developer keys support chat, image, vision, transcription, speech, research, and status scopes. Video remains signed-in-session only.
- Per-key rate limits are enforced through a private counter table and a service-role-only RPC. Signed-in user requests use the existing account rate-limit RPC.
- Search requests use `store: false` for the grounded provider request.
- Video output is private, capped at 50 MB, and signed playback URLs expire after one hour.
- Provider status checks validate credential/access endpoints; they do not prove inference quota or successful image/audio/video generation.

## Self-hosted New Star model

The gateway has an adapter for an OpenAI-compatible New Star-hosted model endpoint. The `ai-model/` directory contains a vLLM deployment blueprint using Qwen3-8B as an open-weight starting point. No GPU host is provisioned, no model weights are hosted by New Star, and no New Star foundation-model training or fine-tuning has been completed. Hosting open weights would not by itself make those weights a New Star-trained model.

## Video verification gate

The Vertex AI video request path is implemented, but an authorized end-to-end test still needs to confirm all of the following:
1. The Google Cloud project has the Vertex AI API, model access, billing, and quota enabled.
2. A real request creates a provider interaction and a corresponding owned job.
3. The job reaches completion and a real MP4 is saved to the private bucket.
4. The signed playback URL plays for the owner and is not accessible to a different user.

Do not claim a video was generated until these checks pass.

## Deployment and release status

- Supabase AI Gateway v97 is active.
- GitHub Actions validation passes for browser JavaScript syntax, Deno type-checking, model Compose configuration, required backend routes, API-key security, and Creator Studio controls.
- The AI Gateway CORS allowlist includes the exact Netlify PR preview and current Vercel preview origins; no broad preview-domain wildcard is used.
- The latest feature branch commit is published as a Netlify deploy preview. Vercel reported its preview build quota/rate limit and Cloudflare's connected build reported an authentication failure; neither failure changes the active Supabase AI Gateway.
- The feature branch is proposed in pull request #6. Production has not been merged or released from this pull request.
- Real authenticated provider inference tests for image, voice, live search, and video remain separate release checks.

## Developer API request example

Keep both credentials in a private server environment, never in browser JavaScript or a public mobile bundle:

```js
const response = await fetch(
  "https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/ai-gateway",
  {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "apikey": process.env.SUPABASE_PUBLISHABLE_KEY,
      "x-new-star-api-key": process.env.NEW_STAR_AI_API_KEY
    },
    body: JSON.stringify({
      messages: [{ role: "user", content: "Hello New Star" }]
    })
  }
);
const result = await response.json();
```

## Operational rule

Distinguish configured, deployed, provider-connected, and end-to-end tested features. Never claim successful generation, search, upload, playback, or deployment without a verified result.
