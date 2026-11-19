create or replace function public.start_video_watch_session(p_video_id uuid, p_client_session_id uuid)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
 v_viewer uuid:=auth.uid();
 v_creator uuid;
 v_session private.video_watch_sessions%rowtype;
begin
 if v_viewer is null then raise exception 'Login required'; end if;
 if p_video_id is null or p_client_session_id is null then raise exception 'Invalid session'; end if;

 -- Close abandoned sessions before creating a new one; rejected sessions cannot add watch credit.
 update private.video_watch_sessions
 set status='rejected'
 where status='active'
   and last_heartbeat_at < now()-interval '2 minutes';

 select case when v.user_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
             then v.user_id::uuid else null end into v_creator
 from public.videos v
 left join public.creator_public_profiles p on p.user_id=v.user_id
 where v.id=p_video_id
   and coalesce(v.processing_status,'published')='published'
   and coalesce(v.visibility,'public') in ('public','unlisted')
   and coalesce(v.audience_type,'all')='all'
   and (
     coalesce(p.is_private,false)=false
     or exists(select 1 from public.followers f
       where f.follower_id=v_viewer::text and f.following_id=v.user_id)
   );
 if v_creator is null then raise exception 'Video unavailable'; end if;

 insert into private.video_watch_sessions(
   video_id,viewer_id,creator_id,client_session_id,started_at,last_heartbeat_at,
   watched_seconds,last_position_seconds,status,views_counted
 ) values(p_video_id,v_viewer,v_creator,p_client_session_id,now(),now(),0,0,'active',false)
 on conflict(video_id,viewer_id,client_session_id)
 do update set last_heartbeat_at=now(),status='active'
 returning * into v_session;
 return jsonb_build_object('session_id',v_session.id,'watched_seconds',v_session.watched_seconds);
end;
$function$;

update private.video_watch_sessions
set status='rejected'
where status='active' and last_heartbeat_at < now()-interval '2 minutes';
