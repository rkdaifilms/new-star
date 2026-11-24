# New Star AI Engine — backend contract

This branch adds a standalone, protected frontend workspace. It does not bundle model weights or expose provider keys in the browser.

## Frontend routes
- `new-star-ai.html`: workspace UI
- `new-star-ai.css`: responsive styling
- `new-star-ai.js`: mode selection, auth token forwarding, upload and result rendering
- `navbar.js`: adds the page to the authenticated app menu

## Required private backend endpoints
All endpoints are POST requests and receive the Supabase access token as a Bearer token. The browser sends `multipart/form-data` with:
- `prompt`: user instruction
- `mode`: chat | research | image | video
- `size`: 1024 | 2048 | 4096
- `history`: JSON conversation history
- `image`: optional uploaded image

Implement:
- `POST /api/new-star-ai/chat`
- `POST /api/new-star-ai/research`
- `POST /api/new-star-ai/images`
- `POST /api/new-star-ai/videos`

## JSON response shape
- Chat: `{ "ok": true, "answer": "..." }`
- Research: `{ "ok": true, "answer": "...", "sources": [{ "title": "...", "url": "https://..." }] }`
- Image: `{ "ok": true, "message": "Image ready", "imageUrl": "https://..." }`
- Video: `{ "ok": true, "message": "Video ready", "videoUrl": "https://..." }`

For long-running image/video jobs, return a job ID and add a status endpoint or implement a polling contract before enabling asynchronous jobs. Store model/provider credentials only as server-side secrets. Add per-user quotas, rate limits, output validation, safe content handling, job timeouts, cancellation, and storage cleanup.

## Important current state
This is a frontend foundation only. The endpoints and inference server are intentionally not fabricated in this branch. Until the private backend and model runtime are deployed, chat/research/image/video requests return a clear backend-not-connected message. This avoids fake generation buttons or fake outputs.


## Async generation support
The browser accepts either an immediate result or a queued job response:
`{ "ok": true, "jobId": "opaque-id", "status": "queued" }`
The UI polls `GET /api/new-star-ai/jobs/{jobId}` using the same authenticated session. Return:
- queued/running: `{ "status": "running", "progress": 35, "message": "Rendering…" }`
- complete: `{ "status": "completed", "imageUrl": "https://..." }` or `videoUrl`
- failure: `{ "status": "failed", "error": "..." }`

## Optional frontend configuration
Before `new-star-ai.js`, set `window.NEW_STAR_AI_CONFIG`:
```js
window.NEW_STAR_AI_CONFIG = {
  apiBase: "https://your-private-api.example",
  pollIntervalMs: 2500,
  jobTimeoutMs: 600000,
  routes: {
    chat: "/api/new-star-ai/chat",
    research: "/api/new-star-ai/research",
    image: "/api/new-star-ai/images",
    video: "/api/new-star-ai/videos"
  }
};
```
Do not put provider keys in this object. It is public browser configuration only.
