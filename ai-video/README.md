# New Star Video Engine

## Current implementation status

New Star AI Gateway now has a real Google Cloud Vertex AI adapter for Gemini Omni text-to-video interactions. It submits background jobs, stores job ownership and provider interaction IDs in the Supabase `ai_video_jobs` table, checks provider progress, saves completed MP4 output to the private `new-star-ai-videos` bucket, and returns a one-hour signed playback URL after checking the signed-in user's job ownership.

This is a provider-backed integration, not a New Star-trained video foundation model. A successful code deployment and a configured service account do not prove that the Google Cloud project has the required Vertex AI API access, model availability, billing, quota, or successful generation. The complete generation path still needs a real end-to-end test with an authorized project.

## Implemented request path

1. Creator Studio sends an authenticated request to the New Star AI Gateway.
2. Gateway validates the user session, prompt length, duration, aspect ratio, resolution, and rate limit.
3. Gateway requests a background Gemini Omni interaction from Vertex AI and stores the provider interaction ID against the authenticated user's job row.
4. Creator Studio polls the status endpoint. The gateway checks job ownership before contacting Vertex AI.
5. When the interaction completes, the gateway validates the inline MP4 payload and enforces the 50 MB private-storage limit.
6. The gateway stores the MP4 under a user-specific path in the private Supabase bucket and returns a short-lived signed URL.

## Current limits

- Text-to-video is connected through Gemini Omni 1.2 Flash, with a 1.1 preview fallback for supported durations.
- Aspect ratios: 16:9 and 9:16.
- Resolutions: 360p, 720p, and 1080p.
- Durations exposed by Creator Studio: 6, 10, and 15 seconds.
- Output: MP4, stored privately in Supabase.
- Maximum stored output: 50 MB.
- Signed playback URL lifetime: one hour.
- Video generation requires `GOOGLE_SERVICE_ACCOUNT_JSON`, a valid Google Cloud project, Vertex AI API access, model permissions, and available billing/quota.

## Not yet implemented or verified

- No New Star-owned trained video model or GPU inference cluster.
- No image-to-video input workflow.
- No creator voiceover recording, muxing, or final export worker.
- No end-to-end provider generation has been verified in this deployment session.
- No guarantee of generation time or unlimited generation. Provider processing may take more than a minute and may fail because of access, quota, or billing.

## Release gate

Treat the video path as provider-connected but not end-to-end verified until an authorized account successfully generates a real clip, the job reaches completed, the MP4 is stored in the private bucket, and the signed playback URL plays for the job owner but not for another account. Keep provider access and usage limits explicit. Do not show a fabricated preview or claim success before the actual MP4 exists.
