# New Star AI Gateway Setup

The authenticated Supabase Edge Function `ai-gateway` is deployed with JWT verification enabled.

## Provider failover order

The server tries production chat providers in this order:

1. `GEMINI_API_KEY`
2. `OPENAI_API_KEY`
3. `OPENROUTER_API_KEY`

The local Qwen model is only a test engine. It is excluded from chat failover unless `NEW_STAR_MODEL_ENABLE_FALLBACK=true` is explicitly set. When enabled, it is tried last, after the production providers.

If a configured provider times out, returns an error, or returns an empty response, the gateway tries the next configured provider. API keys are read only from Supabase Edge Function environment secrets. They must never be placed in HTML, JavaScript, GitHub files, browser storage, or chat messages.

## Required adult-managed setup

An authorized adult account owner should configure newly rotated provider keys in the Supabase Dashboard under Edge Functions > Secrets. Do not paste keys into GitHub or this repository. Any key previously exposed in a chat or public file should be revoked at its provider and replaced.

The gateway currently calls these chat model identifiers:
- New Star local test engine: the value configured in `NEW_STAR_MODEL_NAME`, used only when `NEW_STAR_MODEL_ENABLE_FALLBACK=true`
- Gemini: `gemini-3.8-flash`, then `gemini-3.5-flash-lite`
- OpenAI: `gpt-4o-mini`
- OpenRouter: `openai/gpt-4o-mini`

Image generation and speech generation/transcription use separate provider-specific action handlers. These integrations require their respective provider credentials and successful live requests; a configured key alone is not proof that they work.

Provider/model availability, quotas, billing, and regional access remain controlled by each provider.

## Protections

- Requires a valid Supabase user JWT.
- Allows only the New Star Netlify and Vercel production origins.
- Accepts up to 10 user/assistant messages, 8,000 characters per message, and 20,000 characters total.
- Limits each authenticated account to 20 requests per minute using a private database counter.
- Applies per-provider request timeouts (chat providers use different limits; image and speech actions have their own limits).
- Never logs prompts, model responses, or API keys.
- Does not have access to wallets, payment settlement, or payout functions.

## Endpoint

`https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/ai-gateway`

Send a POST request with the user's current Supabase access token:

```json
{
  "messages": [
    { "role": "user", "content": "Hello" }
  ]
}
```

A successful response includes `ok`, `provider`, and `answer`. If all providers fail, the function returns a safe 503 response. If no provider secrets are configured, it returns `AI_PROVIDERS_NOT_CONFIGURED`.

## Important status

The gateway is connected to the shared floating AI assistant (`ai-chat.js`). Provider secrets and live provider failover have not been end-to-end verified. The Qwen CPU model is an opt-in test engine, not the production ChatGPT/Gemini model. The Help Center reports per-modality support/configuration separately; “configured” is not a live health check. Video generation has a separate Gemini Interactions adapter; live web search is not implemented.


## Video generation

Creator Studio has a separate video-generation action; it does not use the Qwen text model or the image-generation handler. The gateway calls the Gemini Interactions API at `https://generativelanguage.googleapis.com/v1/interactions` using a server-side Gemini API key and the configurable model `NEW_STAR_VIDEO_MODEL` (default: `gemini-omni-1.2-flash`). It starts the interaction with `background: true`, stores the provider interaction ID in the private `ai_video_jobs` table, and checks progress through a separate authenticated `video-status` action. The MP4 is uploaded to private Supabase Storage only after the provider reports completion. The browser never receives the provider key.

Configure `NEW_STAR_VIDEO_MODEL` as a Supabase Edge Function secret only if the selected model is available to the project. The default model identifier is a starting configuration, not proof of project access. The Creator Studio currently requests a six-second clip and the gateway enforces that value, rather than exposing unverified duration choices. The Interactions API video request format is based on Google's current video-generation guidance; model access and successful generation still require live project verification. The model's video generation may require a paid provider tier and usage can be billed by the provider. An authorized adult account owner should review billing and model access before enabling it.

Generated MP4 files are uploaded to the private Supabase Storage bucket `new-star-ai-videos`; the user receives a signed URL valid for one hour. The migration `20261124120000_add_private_ai_video_storage.sql` creates the private bucket and user-scoped read policy. Video rendering runs as a provider-side background interaction. New Star stores a user-owned job record and polls the provider on demand; this is not a continuously running worker, and a user must keep the Creator Studio page open while its current polling loop runs. A configured key is not a live end-to-end test.
