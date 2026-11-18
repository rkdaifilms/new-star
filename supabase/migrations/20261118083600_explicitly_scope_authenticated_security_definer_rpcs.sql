-- Explicitly scope the seven authenticated RPC SECURITY DEFINER functions.
-- Their bodies verify auth.uid() and enforce per-user access. Keep the required client RPC
-- grant, while removing inherited/public and anonymous execution paths.
revoke all on function public.apply_for_creator_monetization() from public, anon;
grant execute on function public.apply_for_creator_monetization() to authenticated, service_role;

revoke all on function public.get_creator_monetization_progress() from public, anon;
grant execute on function public.get_creator_monetization_progress() to authenticated, service_role;

revoke all on function public.get_recommended_video_feed(integer, integer, text, text) from public, anon;
grant execute on function public.get_recommended_video_feed(integer, integer, text, text) to authenticated, service_role;

revoke all on function public.start_video_watch_session(uuid, uuid) from public, anon;
grant execute on function public.start_video_watch_session(uuid, uuid) to authenticated, service_role;

revoke all on function public.heartbeat_video_watch_session(uuid, numeric, boolean) from public, anon;
grant execute on function public.heartbeat_video_watch_session(uuid, numeric, boolean) to authenticated, service_role;

revoke all on function public.end_video_watch_session(uuid, numeric) from public, anon;
grant execute on function public.end_video_watch_session(uuid, numeric) to authenticated, service_role;

revoke all on function public.record_video_engagement(uuid, text) from public, anon;
grant execute on function public.record_video_engagement(uuid, text) to authenticated, service_role;