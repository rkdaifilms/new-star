# New Star AI Engine

## Architecture and ownership
New Star now has its own AI orchestration layer, conversation storage, prompt policy, session checks, rate limiting, and model adapters in its Supabase backend. This is an application-owned AI engine; it is not a newly trained foundation model. Running an independent model without third-party inference APIs requires separately provisioned open-weight model files and GPU inference infrastructure.

Vercel is not required for the AI backend. The live AI gateway runs as a Supabase Edge Function in the existing New Star Supabase project.

## Connected backend
- Supabase Edge Function: `ai-gateway` (active deployment version 77).
- Supabase auth: validates the signed-in user before any AI operation.
- Provider keys remain in server-side Supabase secrets; no provider keys are sent to the browser.
- Provider adapters support configured Gemini, OpenAI and OpenRouter keys for chat/image where supported by the existing gateway.
- A New Star hosted open-model adapter is deployed. It can route chat to a New Star-managed OpenAI-compatible inference endpoint using server-only `NEW_STAR_MODEL_BASE_URL`, `NEW_STAR_MODEL_NAME`, and optional `NEW_STAR_MODEL_API_KEY` secrets. It runs before fallback providers when configured and accepts HTTPS endpoints only. GPU runtime deployment files are in `ai-model/`; no GPU host is currently provisioned.
- This adapter is the connection point for a self-hosted open-weight model (for example, a model served by vLLM or another OpenAI-compatible runtime). It does not include model weights or GPU hosting; those are separate infrastructure and must be provisioned before New Star can run an independent model.
- Per-user request limiting is enforced through the existing `consume_ai_gateway_rate_limit` RPC.
- The system prompt is owned by New Star and switches language based on the user's latest message or saved language preference.

## New Star conversation memory
Migration `new_star_ai_conversation_memory` created:
- `public.new_star_ai_conversations`
- `public.new_star_ai_messages`

Both tables have RLS enabled. Authenticated users can read/delete only their own conversations and read only their own messages. Writes are performed server-side by the authenticated Edge Function using its service role after verifying the caller's session and ownership. The gateway loads the previous messages for the owned conversation, appends the latest user message, sends context to the model, and stores the user/assistant exchange.

The New Star AI page remembers the current conversation ID in the browser so a chat can continue after a page reload. A New chat action starts a separate conversation.

## Connected user features
- **Chat:** uses New Star's own system prompt and saved conversation context.
- **Voice input:** records in the browser and calls the gateway transcription action; the transcript is inserted for review before sending.
- **Voice output:** calls the gateway speech action and plays returned audio.
- **Text-to-image:** calls the image action, sends the chosen 1K/2K/4K size, and renders the returned image. Actual success depends on provider availability and quota.

## Not connected
- **Live Research:** no live web-search provider is integrated.
- **Video generation:** no video inference runtime or job queue is connected.
- **Uploaded-image editing:** the current page does not send image content for editing.

The UI labels these as unavailable rather than pretending to complete them.

## Deployment and verification
- Supabase AI Gateway version 77 is active. The New Star hosted-model adapter is deployed, but no inference endpoint or GPU host has been provisioned.
- The conversation-memory migration applied successfully.
- The page status indicator reports configured providers and explicitly says they are not health-tested; configuration is not proof of a successful inference call.
- Conversation creation now happens only after an AI provider successfully answers, preventing failed first requests from leaving empty conversation rows. The UI also warns when a reply succeeds but its memory could not be saved.
- GitHub Actions validation previously passed for JavaScript syntax, Deno type-checking of the Edge Function, and required auth/rate-limit/action guards. The hosted-model adapter passed CI after its endpoint normalization fix. The runtime Compose files are configuration artifacts and have not been run on a GPU host. Static validation is not a live provider or signed-in user test.
- Security advisors were run. They reported existing project-wide findings (including private tables with no policies, security-definer RPC exposure warnings, and leaked-password protection disabled). These were not introduced or modified as part of this AI feature and should be reviewed separately.
- Frontend changes are committed to GitHub branch `new-star-ai-engine-foundation`.
- A fresh Vercel deployment is not part of this architecture. The existing app hosting setup still needs to publish the latest branch frontend files before users can access the updated page.

## Security
- Never place provider keys or service-role keys in browser code.
- Keep user verification, conversation ownership checks, and rate limits in the Edge Function.
- Do not expose conversations across users.
- Do not claim a generation or live search succeeded unless a real backend response confirms it.
