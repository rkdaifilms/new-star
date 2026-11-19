-- Public feed is intentionally limited to published, public, all-audience videos
-- and excludes videos from private profiles unless the viewer follows the creator.
-- The function is SECURITY DEFINER with an empty search_path and reads private
-- engagement tables internally; exposing only EXECUTE preserves table isolation.
grant execute on function public.get_recommended_video_feed(integer, integer, text, text) to anon;
