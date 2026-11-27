# New Star AI Gateway Setup

New Star uses one AI entry point: the home-page **New Star AI Engine**. The Help & Support assistant is a separate, limited support experience that uses Gemini.

## Separate routing

- **Home New Star AI Engine:** private New Star hosted chat model only. It does not fall back to Gemini, OpenAI, or OpenRouter.
- **Help & Support AI:** Gemini only, with the limited support system prompt. It must not use the general creative assistant route.
- **Private image, vision, speech, transcription, video, and search workers:** currently reported unavailable until their New Star-owned model workers are deployed and verified. Do not advertise them as ready just because a provider key exists.

## Required server configuration

The home chat engine requires these Supabase Edge Function secrets:

- `NEW_STAR_AI_MODE=self_hosted`
- `NEW_STAR_MODEL_BASE_URL` — HTTPS base URL for the private New Star inference service
- `NEW_STAR_MODEL_NAME` — model identifier served by that private service
- `NEW_STAR_MODEL_API_KEY` — private service credential (at least 32 characters)

The private model endpoint must implement an OpenAI-compatible `GET /v1/models` and `POST /v1/chat/completions` API. The gateway checks model availability before reporting the home chat as configured.

Help & Support requires one Gemini server secret, such as `GEMINI_API_KEY` or `GOOGLE_GEMINI_API_KEY`. Gemini is not used as a fallback for the home New Star AI Engine.

Never put model credentials in browser JavaScript, HTML, GitHub files, local storage, or chat messages. Configure or rotate secrets only in Supabase Edge Function Secrets, using the authorized project owner account.

## Authentication and protections

- The gateway validates a signed-in Supabase user session itself.
- The browser origin must be on the approved New Star origin allowlist.
- The gateway applies bounded JSON request sizes and server-side per-user rate limits to protect the service from abuse.
- Rate limits are abuse protection, not an AI credit, coin, or paid-token balance.
- Chat conversation history is saved in the New Star AI conversation tables when database writes succeed.

## Endpoints

- Supabase Edge Function: `https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/ai-gateway`
- Home chat request: `{ "messages": [{ "role": "user", "content": "Hello" }] }`
- Help & Support chat request: same message structure with `"mode": "help"`
- Status request: `{ "action": "status" }`

A successful chat response contains `ok`, `provider`, and `answer`. If the private New Star model is not configured or reachable, the home AI fails closed with a New Star-specific configuration error; it never silently switches to an outside model.

## Verification status

An Edge Function deployment being active confirms that the gateway code is deployed. It does not prove that the private model server, Gemini billing/quota, or image/video generation workers are reachable. Verify the live model health and make an authenticated chat request before marking each capability as working.
