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

Creator Studio has a separate video-generation action; it does not use the Qwen text model or the image-generation handler. Video generation uses Gemini Omni through the Google Cloud Vertex AI Interactions API:

`https://aiplatform.googleapis.com/v1beta1/projects/PROJECT_ID/locations/global/interactions`

This is a Google Cloud service, not the Gemini Developer API endpoint. It requires a Google Cloud project with the Vertex AI API enabled and a service account authorized to invoke Vertex AI. The gateway exchanges the service account's private-key JWT for a short-lived OAuth access token server-side; the browser never receives Google credentials.

Required Supabase Edge Function secrets:
- `GOOGLE_SERVICE_ACCOUNT_JSON`: complete service-account JSON key, stored only as a server secret
- `GOOGLE_CLOUD_PROJECT_ID`: Google Cloud project ID (optional if present in the service-account JSON)
- `NEW_STAR_VIDEO_MODEL`: optional model override; default order is `gemini-omni-1.2-flash`, then `gemini-omni-1.1-flash-preview`

The gateway starts a provider-side background interaction, stores its interaction ID in the private `ai_video_jobs` table, and checks progress through a separate authenticated `video-status` action. The MP4 is uploaded to the private Supabase Storage bucket only after the provider reports completion. The Creator Studio currently requests a six-second clip. The API supports 3–15 seconds for `gemini-omni-1.2-flash` and 3–10 seconds for preview models; the UI currently keeps duration fixed at six seconds until broader controls are tested.

Google Cloud service-account private keys are sensitive. An authorized adult account owner should create and configure the service account, grant only the minimum Vertex AI permissions, enable the API, and review project billing and model access before enabling generation. Never paste service-account JSON or private keys into chat, GitHub, frontend code, or browser storage.

Generated MP4 files are uploaded to the private Supabase Storage bucket `new-star-ai-videos`; the user receives a signed URL valid for one hour. The migration `20261124120000_add_private_ai_video_storage.sql` creates the private bucket and user-scoped read policy. Video rendering runs as a provider-side background interaction. New Star stores a user-owned job record and polls the provider on demand; it is not a continuously running worker. The browser stores only the pending job ID locally and can resume polling after a page reload; the authenticated gateway verifies that the job belongs to the signed-in user. A configured service account is not proof of live generation; an actual provider request is still required to verify the end-to-end flow.
