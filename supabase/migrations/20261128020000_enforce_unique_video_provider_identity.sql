-- A provider upload must map to at most one New Star video row.
-- PostgreSQL permits multiple NULL values in a unique index, so videos without
-- a provider identity remain unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS videos_provider_video_id_unique
    ON public.videos (video_provider, provider_video_id);
