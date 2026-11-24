# New Star Video Engine

## Goal
A New Star-owned asynchronous video-generation service for creator workflows. Initial target: 10-second clips, prompt-to-video first, image-to-video later, followed by creator voiceover and export.

## Important current status
This is an implementation plan and integration contract, not a running video generator. The repository currently has no video model runtime, provider credentials, GPU host, job worker, or video storage bucket. Do not enable a Generate Video control as if generation works until a provider adapter and worker pass a real end-to-end test.

## Architecture
1. Creator Studio sends an authenticated request to the New Star AI Gateway.
2. Gateway validates session, ownership, prompt length, duration (initially 10 seconds), aspect ratio, and per-user quotas.
3. Gateway creates a durable queued job in new_star_video_jobs and returns a job ID.
4. A private worker claims jobs, calls the configured video inference runtime, and updates progress/status.
5. Completed output is uploaded to a private Supabase Storage bucket; the API returns a short-lived signed URL after checking ownership.
6. Creator records or uploads voiceover. A media worker muxes voiceover with the generated clip, preserves/normalizes audio levels, and exports a final MP4. Keep the original silent clip and voice track separately for re-editing.

## Initial creator settings
- Duration: 10 seconds (hard cap for first release).
- Prompt-to-video: required for first release.
- Image-to-video: optional second step.
- Aspect ratios: 16:9 and 9:16.
- Output: MP4/H.264 with AAC audio after voiceover.
- Voiceover: browser recording or user-uploaded audio; explicit preview before applying.
- Job states: queued, processing, finalizing, completed, failed, cancelled.
- Show progress and useful errors. Never return a fabricated preview or mark a job complete before the output file exists.

## Runtime contract
The worker must implement submitVideo({ prompt, durationSeconds, aspectRatio, imageInput? }) -> { providerJobId }; getVideoStatus(providerJobId) -> { status, progress?, outputUrl?, error? }; downloadAndValidateOutput(outputUrl) -> MP4 bytes; muxVoiceover(videoBytes, audioBytes) -> MP4 bytes.

Use a private server-side adapter. Do not expose provider keys or raw worker endpoints in browser code. The model provider is replaceable behind this interface; New Star owns authentication, jobs, quotas, storage, creator workflow and API contract.

## Infrastructure required before live generation
- A selected video model/runtime with documented API and commercial/use rights reviewed.
- GPU capacity or a video inference provider, plus explicit budget/usage limits.
- Private worker/queue deployment and retry/idempotency handling.
- Supabase migration for jobs, RLS policies, private Storage bucket and cleanup policy.
- Voiceover muxing worker (FFmpeg or equivalent) with file size/duration limits.
- End-to-end tests: submit 10s job, poll, verify MP4, attach voiceover, export, and verify owner-only access.

## Cost and reliability
Video inference is resource-intensive. Unlimited generation cannot be promised. Enforce per-user quotas, concurrent-job limits, maximum upload sizes, retention limits and transparent usage estimates. Do not start paid infrastructure or paid provider requests without explicit approval.

## Release gate
Keep the Video tab marked Coming soon until the whole path above is deployed and verified with an actual generated clip and voiceover export. The existing AI chat, image and research workflows must remain unaffected.