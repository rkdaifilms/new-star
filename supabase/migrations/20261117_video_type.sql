-- New Star: persist the Shorts / Long Video selection.
-- Run this once in Supabase SQL Editor before publishing the updated app.

ALTER TABLE public.videos
  ADD COLUMN IF NOT EXISTS video_type text;

UPDATE public.videos
SET video_type = 'auto'
WHERE video_type IS NULL;

ALTER TABLE public.videos
  ALTER COLUMN video_type SET DEFAULT 'auto';

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'videos_video_type_check'
      AND conrelid = 'public.videos'::regclass
  ) THEN
    ALTER TABLE public.videos
      ADD CONSTRAINT videos_video_type_check
      CHECK (video_type IN ('auto', 'short', 'long'));
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS videos_video_type_created_at_idx
  ON public.videos (video_type, created_at DESC);
