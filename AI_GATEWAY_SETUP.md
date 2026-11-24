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

The gateway is connected to the shared floating AI assistant (`ai-chat.js`). Provider secrets and live provider failover have not been end-to-end verified. The Qwen CPU model is an opt-in test engine, not the production ChatGPT/Gemini model. The Help Center reports per-modality support/configuration separately; “configured” is not a live health check. Video generation has a separate Vertex AI Interactions adapter. New Star Search is wired through a separately deployed search service; the crawler, index, and ranking service must be deployed separately.


## Video generation

Creator Studio has a separate video-generation action; it does not use the Qwen text model or the image-generation handler. Video generation uses Gemini Omni through the Google Cloud Vertex AI Interactions API:

`https://aiplatform.googleapis.com/v1beta1/projects/PROJECT_ID/locations/global/interactions`

This is a Google Cloud service, not the Gemini Developer API endpoint. It requires a Google Cloud project with the Vertex AI API enabled and a service account authorized to invoke Vertex AI. The gateway exchanges the service account's private-key JWT for a short-lived OAuth access token server-side; the browser never receives Google credentials.

Required Supabase Edge Function secrets:
- `GOOGLE_SERVICE_ACCOUNT_JSON`: complete service-account JSON key, stored only as a server secret
- `GOOGLE_CLOUD_PROJECT_ID`: Google Cloud project ID (optional if present in the service-account JSON)
- `NEW_STAR_VIDEO_MODEL`: optional model override; default order is `gemini-omni-1.2-flash`, then `gemini-omni-1.1-flash-preview`

The gateway sends the prompt as a typed Interactions API text input (`input: [{ type: "text", text: prompt }]`), starts a provider-side background interaction, stores its interaction ID in the private `ai_video_jobs` table, and checks progress through a separate authenticated `video-status` action. Google documents stored and asynchronous interactions as retained for up to seven days; if a provider interaction expires or disappears, New Star marks that job failed and lets the user start a fresh one. The MP4 is uploaded to the private Supabase Storage bucket only after the provider reports completion. Creator Studio exposes 6-, 10-, and 15-second options. The API supports 3–15 seconds for `gemini-omni-1.2-flash` and 3–10 seconds for preview models; New Star does not attempt the preview fallback for a 15-second request.

Google Cloud service-account private keys are sensitive. An authorized adult account owner should create and configure the service account, grant only the minimum Vertex AI permissions, enable the API, and review project billing and model access before enabling generation. Never paste service-account JSON or private keys into chat, GitHub, frontend code, or browser storage.

Generated MP4 files are uploaded to the private Supabase Storage bucket `new-star-ai-videos`; the user receives a signed URL valid for one hour. The migration `20261124120000_add_private_ai_video_storage.sql` creates the private bucket and user-scoped read policy. Video rendering runs as a provider-side background interaction. New Star stores a user-owned job record and polls the provider on demand; it is not a continuously running worker. The browser stores only the pending job ID locally and can resume polling after a page reload; the authenticated gateway verifies that the job belongs to the signed-in user. A configured service account is not proof of live generation; an actual provider request is still required to verify the end-to-end flow.


## Unified multimodal actions and New Star Search

Creator Studio uses one authenticated Supabase Edge Function for chat, image generation, video generation/status, speech generation, speech transcription, and search. The browser sends a typed action to the same gateway; each modality keeps its own validation, provider credentials, rate limit, timeout, and response shape.

Search is routed to the self-hosted New Star Search service. Configure these Supabase Edge Function secrets:
- NEW_STAR_SEARCH_BASE_URL: HTTPS origin or base path for the New Star Search API. The gateway posts to /search unless the configured path already ends in /search.
- NEW_STAR_SEARCH_API_KEY: optional bearer token required by the search service.

The search service must accept POST JSON containing query, language, and limit, and return JSON with a results array. Each result must contain title and an HTTPS url; snippet and publishedAt are optional. The gateway validates result URLs and limits response fields before returning them. Creator Studio has a Search Web mode that displays clickable indexed results.

This is an integration contract, not a claim that the crawler or index already exists. If NEW_STAR_SEARCH_BASE_URL is not configured, search returns a clear 503 and the UI does not invent results. Do not configure an endpoint until the New Star Search service has been deployed and secured.


## Connect the New Star AI API to Creator Studio

Creator Studio and the Help Center already call the authenticated ai-gateway function. To route chat through the self-hosted New Star API first, configure these Supabase Edge Function secrets after deploying the New Star API at a publicly reachable HTTPS endpoint:

- NEW_STAR_MODEL_BASE_URL: HTTPS base URL of the New Star API, without credentials, query, or fragment. The gateway calls its /chat/completions compatibility route.
- NEW_STAR_MODEL_NAME: model ID accepted by the New Star API.
- NEW_STAR_MODEL_API_KEY: bearer token configured on the New Star API. Keep this server-side only.
- NEW_STAR_MODEL_PRIMARY: set to true to try New Star first; Gemini, OpenAI, and OpenRouter remain bounded fallbacks.
- NEW_STAR_MODEL_ENABLE_FALLBACK: when New Star is not primary, set true only if you want to opt into it as a last-resort test backend.

The gateway verifies HTTPS endpoint syntax and sends the bearer token server-side. The New Star endpoint must be internet-reachable from Supabase Edge Functions; localhost/private-network addresses are not reachable from the hosted gateway. The Creator Studio UI continues to use one authenticated gateway endpoint, so provider credentials never enter browser JavaScript. A configured endpoint does not prove model quality, reachability, or production readiness; run live end-to-end checks after an authorized deployment.

## Optional New Star retrieval-augmented chat

The authenticated chat route can optionally retrieve excerpts from the separately hosted New Star Search index and add them to the model's system context. Enable this only after the search service is deployed and populated:

- `NEW_STAR_RAG_ENABLED=true`
- `NEW_STAR_SEARCH_BASE_URL=https://your-search-host`
- `NEW_STAR_SEARCH_API_KEY` (server-side secret only)

When enabled, each chat request attempts one bounded search (up to five results, eight-second timeout). Search failure is non-fatal: ordinary chat continues without retrieved context. Retrieved snippets are explicitly marked untrusted and source URLs are passed to the model. This is lexical SQLite FTS retrieval, not embedding/vector retrieval; quality depends on the pages crawled into the allowlisted index.
