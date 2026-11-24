-- Keep pending Shorts cohorts reviewable after their 24-hour window.
create or replace function public.refresh_video_distribution_reviews()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_video_id uuid;
  v_evaluated integer := 0;
begin
  if auth.uid() is null then
    raise exception 'Login required';
  end if;

  for v_video_id in
    select video_id
    from private.video_distribution_state
    where status='active'
      and stage_reached_at is not null
      and stage_reached_at<=now()-interval '24 hours'
    order by stage_reached_at
    limit 200
  loop
    perform private.evaluate_video_distribution(v_video_id);
    v_evaluated:=v_evaluated+1;
  end loop;

  return jsonb_build_object('evaluated',v_evaluated);
end;
$function$;

revoke all on function public.refresh_video_distribution_reviews() from public,anon;
grant execute on function public.refresh_video_distribution_reviews() to authenticated;

-- Align public-profile privacy checks across feed, watch-session, and engagement RPCs.
do $migration$
declare
  v_name text;
  v_def text;
  v_old text := 'coalesce(p.is_private,false)=false';
  v_new text := '(coalesce(p.is_private,false)=false and not exists(select 1 from public.profiles p2 where p2.user_id=v.user_id and p2.is_private=true))';
  v_names text[] := array[
    'public.start_video_watch_session(uuid,uuid)',
    'public.heartbeat_video_watch_session(uuid,numeric,boolean)',
    'public.end_video_watch_session(uuid,numeric)',
    'public.record_video_engagement(uuid,text)'
  ];
begin
  foreach v_name in array v_names loop
    execute format('select pg_get_functiondef(%L::regprocedure)',v_name) into v_def;
    if position(v_old in v_def)=0 then
      raise exception 'Privacy check not found in %',v_name;
    end if;
    v_def:=replace(v_def,v_old,v_new);
    execute v_def;
  end loop;
end;
$migration$;

create index if not exists video_engagement_events_video_viewer_type_created_idx
on private.video_engagement_events(video_id,viewer_id,event_type,created_at);

create index if not exists video_feed_impressions_stage_lookup_idx
on private.video_feed_impressions(video_id,surface,stage_number,shown_at);

revoke all on table private.video_feed_impressions from anon,authenticated;
revoke all on table private.video_distribution_state from anon,authenticated;
