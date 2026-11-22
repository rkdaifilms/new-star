-- Evaluate each Shorts audience cohort from unique, real viewer activity.
alter table private.video_distribution_state
  add column if not exists stage_reached_at timestamptz;

CREATE OR REPLACE FUNCTION private.evaluate_video_distribution(p_video_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_state private.video_distribution_state%rowtype;
  v_impressions bigint;
  v_completions bigint;
  v_likes bigint;
  v_comments bigint;
  v_completion_rate numeric(8,5);
  v_like_rate numeric(8,5);
  v_comment_rate numeric(8,5);
  v_next_target bigint;
begin
  if p_video_id is null then
    return jsonb_build_object('evaluated',false,'reason','missing_video');
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_video_id::text));
  select * into v_state
  from private.video_distribution_state
  where video_id=p_video_id
  for update;

  if not found or v_state.video_type<>'short' or v_state.status<>'active' then
    return jsonb_build_object('evaluated',false,'reason','not_active');
  end if;

  select count(*) into v_impressions
  from private.video_feed_impressions i
  where i.video_id=p_video_id and i.surface='short_feed'
    and i.stage_number=v_state.stage_number;

  if v_impressions<v_state.stage_target then
    return jsonb_build_object('evaluated',false,'reason','stage_not_full',
      'impressions',v_impressions,'target',v_state.stage_target);
  end if;

  if v_state.stage_reached_at is null then
    update private.video_distribution_state
    set stage_reached_at=now(),updated_at=now()
    where video_id=p_video_id;
    v_state.stage_reached_at:=now();
  end if;

  select
    count(distinct i.viewer_id) filter(where exists(
      select 1 from private.video_engagement_events e
      where e.video_id=i.video_id and e.viewer_id=i.viewer_id
        and e.event_type='complete' and e.created_at>=i.shown_at
    )),
    count(distinct i.viewer_id) filter(where exists(
      select 1 from public.likes l
      where l.video_id=i.video_id::text and l.user_id=i.viewer_id::text
        and l.created_at>=i.shown_at
    )),
    count(distinct i.viewer_id) filter(where exists(
      select 1 from public.video_comments c
      where c.video_id=i.video_id::text and c.user_id=i.viewer_id::text
        and c.created_at>=i.shown_at
    ))
  into v_completions,v_likes,v_comments
  from private.video_feed_impressions i
  where i.video_id=p_video_id and i.surface='short_feed'
    and i.stage_number=v_state.stage_number;

  v_completion_rate:=coalesce(v_completions,0)::numeric/greatest(v_impressions,1);
  v_like_rate:=coalesce(v_likes,0)::numeric/greatest(v_impressions,1);
  v_comment_rate:=coalesce(v_comments,0)::numeric/greatest(v_impressions,1);

  if v_completion_rate>=0.50 and v_like_rate>=0.10 and v_comment_rate>=0.10 then
    select min(x) into v_next_target
    from unnest(array[
      200::bigint,500,1000,2000,5000,10000,20000,50000,100000,200000,
      500000,1000000,2000000,5000000,10000000,20000000,50000000,
      100000000,200000000,500000000,1000000000,2000000000,
      5000000000,10000000000,20000000000,50000000000,100000000000,
      200000000000,500000000000,1000000000000
    ]::bigint[]) as x
    where x>v_state.stage_target;
    v_next_target:=coalesce(v_next_target,v_state.stage_target*2);

    update private.video_distribution_state
    set stage_number=stage_number+1,stage_target=v_next_target,
        stage_reached_at=null,last_completion_rate=v_completion_rate,
        last_like_rate=v_like_rate,last_comment_rate=v_comment_rate,
        last_evaluated_at=now(),updated_at=now()
    where video_id=p_video_id;

    return jsonb_build_object('evaluated',true,'advanced',true,
      'stage',v_state.stage_number+1,'next_target',v_next_target,
      'completion_rate',v_completion_rate,'like_rate',v_like_rate,
      'comment_rate',v_comment_rate);
  end if;

  if v_state.stage_reached_at<=now()-interval '24 hours' then
    update private.video_distribution_state
    set status='paused',last_completion_rate=v_completion_rate,
        last_like_rate=v_like_rate,last_comment_rate=v_comment_rate,
        last_evaluated_at=now(),updated_at=now()
    where video_id=p_video_id;
    return jsonb_build_object('evaluated',true,'advanced',false,'paused',true,
      'completion_rate',v_completion_rate,'like_rate',v_like_rate,
      'comment_rate',v_comment_rate);
  end if;

  update private.video_distribution_state
  set last_completion_rate=v_completion_rate,last_like_rate=v_like_rate,
      last_comment_rate=v_comment_rate,last_evaluated_at=now(),updated_at=now()
  where video_id=p_video_id;

  return jsonb_build_object('evaluated',true,'advanced',false,'pending',true,
    'completion_rate',v_completion_rate,'like_rate',v_like_rate,
    'comment_rate',v_comment_rate);
end;
$function$


CREATE OR REPLACE FUNCTION private.evaluate_video_distribution_trigger()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_video_id uuid;
begin
  if tg_table_name='video_engagement_events' then
    v_video_id:=new.video_id;
  elsif tg_op='DELETE' then
    begin v_video_id:=old.video_id::uuid; exception when others then return old; end;
  else
    begin v_video_id:=new.video_id::uuid; exception when others then return new; end;
  end if;
  perform private.evaluate_video_distribution(v_video_id);
  if tg_op='DELETE' then return old; end if;
  return new;
end;
$function$


drop trigger if exists evaluate_short_distribution_engagement on private.video_engagement_events;
create trigger evaluate_short_distribution_engagement
after insert on private.video_engagement_events
for each row execute function private.evaluate_video_distribution_trigger();

drop trigger if exists evaluate_short_distribution_likes on public.likes;
create trigger evaluate_short_distribution_likes
after insert or delete on public.likes
for each row execute function private.evaluate_video_distribution_trigger();

drop trigger if exists evaluate_short_distribution_comments on public.video_comments;
create trigger evaluate_short_distribution_comments
after insert or delete on public.video_comments
for each row execute function private.evaluate_video_distribution_trigger();

CREATE OR REPLACE FUNCTION public.record_video_feed_impression(p_video_id uuid, p_surface text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_viewer uuid := auth.uid();
  v_creator text;
  v_type text;
  v_stage integer := 0;
  v_target bigint := 0;
  v_status text := 'active';
  v_reached_at timestamptz;
  v_inserted integer := 0;
  v_evaluation jsonb;
begin
  if v_viewer is null then
    return jsonb_build_object('recorded',false,'reason','login_required');
  end if;
  if p_video_id is null or p_surface not in ('short_feed','long_feed','search') then
    raise exception 'Invalid feed impression';
  end if;

  select v.user_id,
    case
      when v.duration_seconds is not null then case when v.duration_seconds<=60 then 'short' else 'long' end
      else coalesce(v.video_type,
        case when v.width is not null and v.height is not null and v.width>v.height then 'long' else 'short' end)
    end
  into v_creator,v_type
  from public.videos v
  where v.id=p_video_id
    and coalesce(v.processing_status,'published')='published'
    and coalesce(v.visibility,'public')='public'
    and coalesce(v.audience_type,'all')='all'
    and (
      not exists(select 1 from public.profiles p where p.user_id=v.user_id and p.is_private=true)
      or exists(select 1 from public.followers f where f.follower_id=v_viewer::text and f.following_id=v.user_id)
    );

  if not found then
    return jsonb_build_object('recorded',false,'reason','video_unavailable');
  end if;
  if v_creator=v_viewer::text then
    return jsonb_build_object('recorded',false,'reason','owner_view');
  end if;
  if (p_surface='short_feed' and v_type<>'short')
     or (p_surface='long_feed' and v_type<>'long') then
    raise exception 'Feed surface does not match video type';
  end if;

  if p_surface='short_feed' then
    insert into private.video_distribution_state(video_id,video_type)
    values(p_video_id,'short')
    on conflict(video_id) do nothing;

    select stage_number,stage_target,status,stage_reached_at
      into v_stage,v_target,v_status,v_reached_at
    from private.video_distribution_state
    where video_id=p_video_id
    for update;

    if v_status<>'active' then
      return jsonb_build_object('recorded',false,'reason','distribution_paused','stage',v_stage);
    end if;
    if v_reached_at is not null then
      v_evaluation:=private.evaluate_video_distribution(p_video_id);
      select stage_number,stage_target,status,stage_reached_at
        into v_stage,v_target,v_status,v_reached_at
      from private.video_distribution_state where video_id=p_video_id;
      if v_status<>'active' or v_reached_at is not null then
        return jsonb_build_object('recorded',false,'reason','stage_review_pending','stage',v_stage);
      end if;
    end if;
  end if;

  insert into private.video_feed_impressions(video_id,viewer_id,surface,stage_number)
  values(p_video_id,v_viewer,p_surface,case when p_surface='short_feed' then v_stage else 0 end)
  on conflict(video_id,viewer_id,surface) do nothing;
  get diagnostics v_inserted = row_count;

  if v_inserted=1 and p_surface='short_feed' then
    v_evaluation:=private.evaluate_video_distribution(p_video_id);
    select stage_number,stage_target,status into v_stage,v_target,v_status
    from private.video_distribution_state where video_id=p_video_id;
  end if;

  return jsonb_build_object(
    'recorded',v_inserted=1,'surface',p_surface,
    'stage',case when p_surface='short_feed' then v_stage else 0 end,
    'stage_target',case when p_surface='short_feed' then v_target else 0 end,
    'stage_status',case when p_surface='short_feed' then v_status else 'not_gated' end,
    'evaluation',v_evaluation
  );
end;
$function$


revoke all on function private.evaluate_video_distribution(uuid) from public, anon, authenticated;
revoke all on function private.evaluate_video_distribution_trigger() from public, anon, authenticated;
revoke all on function public.record_video_feed_impression(uuid,text) from public, anon;
grant execute on function public.record_video_feed_impression(uuid,text) to authenticated;
