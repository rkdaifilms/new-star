alter table public.creator_monetization_applications
  add column if not exists threshold_followers integer not null default 5000;

alter table public.creator_monetization_applications
  alter column threshold_watch_hours set default 1000,
  alter column threshold_shorts_views set default 300000,
  alter column threshold_window_days set default 60;

create or replace function public.get_creator_monetization_progress()
returns jsonb
language plpgsql
stable
security definer
set search_path to ''
as $function$
declare
  v_creator uuid := auth.uid();
  v_watch_seconds bigint := 0;
  v_short_views bigint := 0;
  v_followers bigint := 0;
  v_application_status text;
begin
  if v_creator is null then raise exception 'Login required'; end if;

  select coalesce(sum(d.watched_seconds),0)::bigint into v_watch_seconds
  from private.creator_watch_daily d
  join public.videos v on v.id=d.video_id
  where d.creator_id=v_creator
    and d.watch_date >= ((now() at time zone 'utc')::date - 59)
    and d.watch_date <= (now() at time zone 'utc')::date
    and v.duration_seconds > 60 and v.duration_seconds <= 10800
    and coalesce(v.video_type,'long')='long'
    and coalesce(v.processing_status,'published')='published'
    and coalesce(v.visibility,'public')='public'
    and coalesce(v.audience_type,'all')='all';

  select count(*)::bigint into v_short_views
  from private.video_view_dedupe d
  join public.videos v on v.id=d.video_id
  where d.creator_id=v_creator
    and d.view_date >= ((now() at time zone 'utc')::date - 59)
    and d.view_date <= (now() at time zone 'utc')::date
    and d.video_type='short'
    and v.duration_seconds between 1 and 59
    and coalesce(v.video_type,'short')='short'
    and coalesce(v.processing_status,'published')='published'
    and coalesce(v.visibility,'public')='public'
    and coalesce(v.audience_type,'all')='all';

  select count(distinct f.follower_id)::bigint into v_followers
  from public.followers f
  join auth.users u on u.id::text=f.follower_id
  where f.following_id=v_creator::text
    and f.follower_id<>v_creator::text;

  select status into v_application_status
  from public.creator_monetization_applications
  where creator_id=v_creator
  order by applied_at desc limit 1;

  return jsonb_build_object(
    'watch_seconds_60d',v_watch_seconds,
    'watch_hours_60d',round(v_watch_seconds::numeric/3600,2),
    'watch_hours_threshold',1000,
    'short_views_60d',v_short_views,
    'short_views_30d',v_short_views,
    'short_views_threshold',300000,
    'follower_count',v_followers,
    'follower_threshold',5000,
    'followers_eligible',v_followers >= 5000,
    'watch_time_eligible',v_watch_seconds >= 3600000,
    'shorts_eligible',v_short_views >= 300000,
    'eligible_for_review',(v_followers >= 5000 and (v_watch_seconds >= 3600000 or v_short_views >= 300000)),
    'application_status',v_application_status,
    'watch_window_days',60,
    'shorts_window_days',60,
    'follower_window_days',0
  );
end;
$function$;

create or replace function public.apply_for_creator_monetization()
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_creator uuid := auth.uid();
  v_progress jsonb;
  v_path text;
  v_existing public.creator_monetization_applications%rowtype;
  v_new public.creator_monetization_applications%rowtype;
begin
  if v_creator is null then raise exception 'Login required'; end if;

  v_progress := public.get_creator_monetization_progress();
  if not coalesce((v_progress->>'eligible_for_review')::boolean,false) then
    raise exception 'Eligibility thresholds not reached yet.';
  end if;

  if coalesce((v_progress->>'watch_time_eligible')::boolean,false) then
    v_path := 'long_watch_time';
  else
    v_path := 'shorts_views';
  end if;

  select * into v_existing
  from public.creator_monetization_applications
  where creator_id=v_creator and status='pending'
  order by applied_at desc limit 1;
  if found then
    return jsonb_build_object('submitted',false,'already_pending',true,'application_id',v_existing.id);
  end if;

  begin
    insert into public.creator_monetization_applications(
      creator_id,status,threshold_watch_hours,threshold_shorts_views,
      threshold_window_days,threshold_followers,eligibility_path
    ) values (
      v_creator,'pending',1000,300000,60,5000,v_path
    ) returning * into v_new;
  exception when unique_violation then
    select * into v_existing from public.creator_monetization_applications
    where creator_id=v_creator and status='pending' order by applied_at desc limit 1;
    return jsonb_build_object('submitted',false,'already_pending',true,'application_id',v_existing.id);
  end;

  return jsonb_build_object('submitted',true,'application_id',v_new.id,'eligibility_path',v_path);
end;
$function$;