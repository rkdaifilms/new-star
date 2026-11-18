# New Star AI Gateway Setup

The authenticated Supabase Edge Function `ai-gateway` is deployed with JWT verification enabled.

## Provider failover order

The server checks these provider secrets in this order:

1. `OPENAI_API_KEY`
2. `GEMINI_API_KEY`
3. `OPENROUTER_API_KEY`

If a configured provider times out, returns an error, or returns an empty response, the gateway tries the next configured provider. API keys are read only from Supabase Edge Function environment secrets. They must never be placed in HTML, JavaScript, GitHub files, browser storage, or chat messages.

## Required adult-managed setup

An authorized adult account owner should configure newly rotated provider keys in the Supabase Dashboard under Edge Functions > Secrets. Do not paste keys into GitHub or this repository. Any key previously exposed in a chat or public file should be revoked at its provider and replaced.

The gateway currently uses:
- OpenAI Chat Completions with `gpt-4o-mini`
- Gemini `gemini-2.5-flash`
- OpenRouter `openai/gpt-4o-mini`

Provider/model availability, quotas, billing, and regional access remain controlled by each provider.

## Protections

- Requires a valid Supabase user JWT.
- Allows only the New Star Netlify and Vercel production origins.
- Accepts up to 10 user/assistant messages, 8,000 characters per message, and 20,000 characters total.
- Limits each authenticated account to 20 requests per minute using a private database counter.
- Applies a 16-second timeout to each provider attempt.
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

The gateway is infrastructure only. It is not yet connected to the app's support/chat UI. Provider secrets have not been confirmed as configured, and live provider failover has not been end-to-end tested. Do not advertise AI support as live until secrets are configured and a test succeeds.
