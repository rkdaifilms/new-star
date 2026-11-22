-- Separate ranking signals for Shorts and long-form video, plus text search.
CREATE OR REPLACE FUNCTION public.get_recommended_video_feed_v2(p_limit integer DEFAULT 30, p_offset integer DEFAULT 0, p_video_type text DEFAULT NULL::text, p_category text DEFAULT NULL::text, p_search text DEFAULT NULL::text)
 RETURNS TABLE(id uuid, video_url text, caption text, category text, username text, likes_count integer, views_count integer, created_at timestamp with time zone, user_id text, affiliate_link text, audience_type text, duration_seconds integer, width integer, height integer, thumbnail_url text, video_type text, score numeric)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
with candidates as (
  select
    v.id,v.video_url,v.caption,v.category,v.username,
    coalesce(v.likes_count,0)::integer as likes_count,
    coalesce(v.views_count,0)::integer as views_count,
    v.created_at,v.user_id,v.affiliate_link,v.audience_type,
    v.duration_seconds,v.width,v.height,v.thumbnail_url,
    case
      when v.duration_seconds is not null then case when v.duration_seconds<=60 then 'short' else 'long' end
      else coalesce(v.video_type,
        case when v.width is not null and v.height is not null and v.width>v.height then 'long' else 'short' end)
    end as resolved_video_type,
    coalesce(im.short_impressions,0)::numeric as short_impressions,
    coalesce(im.long_impressions,0)::numeric as long_impressions,
    coalesce(eg.complete_users,0)::numeric as complete_users,
    coalesce(eg.skip_users,0)::numeric as skip_users,
    coalesce(eg.not_interested_users,0)::numeric as not_interested_users,
    coalesce(eg.report_users,0)::numeric as report_users,
    coalesce(eg.share_users,0)::numeric as share_users,
    coalesce(eg.rewatch_users,0)::numeric as rewatch_users,
    coalesce(eg.avg_watch_seconds,0)::numeric as avg_watch_seconds,
    coalesce(lk.unique_likes,0)::numeric as unique_likes,
    coalesce(cm.unique_comments,0)::numeric as unique_comments,
    coalesce(ds.status,'active') as distribution_status,
    (
      case
        when (case
          when v.duration_seconds is not null then case when v.duration_seconds<=60 then 'short' else 'long' end
          else coalesce(v.video_type,case when v.width is not null and v.height is not null and v.width>v.height then 'long' else 'short' end)
        end)='short'
        then
          38*(coalesce(eg.complete_users,0)::numeric/greatest(coalesce(im.short_impressions,0),1))
          +14*(case when coalesce(im.short_impressions,0)>0 then least(1,coalesce(lk.unique_likes,0)::numeric/im.short_impressions) else 0 end)
          +12*(case when coalesce(im.short_impressions,0)>0 then least(1,coalesce(cm.unique_comments,0)::numeric/im.short_impressions) else 0 end)
          +8*(coalesce(eg.share_users,0)::numeric/greatest(coalesce(im.short_impressions,0),1))
          +5*(coalesce(eg.rewatch_users,0)::numeric/greatest(coalesce(im.short_impressions,0),1))
          -12*(coalesce(eg.skip_users,0)::numeric/greatest(coalesce(im.short_impressions,0),1))
          -25*(coalesce(eg.not_interested_users,0)::numeric/greatest(coalesce(im.short_impressions,0),1))
          -40*(coalesce(eg.report_users,0)::numeric/greatest(coalesce(im.short_impressions,0),1))
          +case when coalesce(im.short_impressions,0)>0 then 8*least(1,v.views_count::numeric/im.short_impressions) else ln(1+v.views_count)*0.35 end
        else
          35*least(1,coalesce(eg.avg_watch_seconds,0)::numeric/greatest(coalesce(v.duration_seconds,1),1))
          +12*(coalesce(eg.complete_users,0)::numeric/greatest(coalesce(im.long_impressions,0),1))
          +10*(case when coalesce(im.long_impressions,0)>0 then least(1,coalesce(lk.unique_likes,0)::numeric/im.long_impressions) else 0 end)
          +7*(case when coalesce(im.long_impressions,0)>0 then least(1,coalesce(cm.unique_comments,0)::numeric/im.long_impressions) else 0 end)
          +9*(coalesce(eg.share_users,0)::numeric/greatest(coalesce(im.long_impressions,0),1))
          -10*(coalesce(eg.skip_users,0)::numeric/greatest(coalesce(im.long_impressions,0),1))
          -25*(coalesce(eg.not_interested_users,0)::numeric/greatest(coalesce(im.long_impressions,0),1))
          -40*(coalesce(eg.report_users,0)::numeric/greatest(coalesce(im.long_impressions,0),1))
          +case when coalesce(im.long_impressions,0)>0 then 8*least(1,v.views_count::numeric/im.long_impressions) else ln(1+v.views_count)*0.35 end
      end
      +case when exists(
        select 1 from public.followers f
        where f.follower_id=auth.uid()::text and f.following_id=v.user_id
      ) then 5 else 0 end
      +case when exists(
        select 1 from private.video_engagement_events e
        join public.videos watched on watched.id=e.video_id
        where e.viewer_id=auth.uid() and e.event_type in ('watch','complete','like','rewatch')
          and watched.category is not null and lower(watched.category)=lower(v.category)
          and e.created_at>now()-interval '30 days'
      ) then 3 else 0 end
      +greatest(0,24-extract(epoch from (now()-v.created_at))/3600)/24.0
    )::numeric as rank_score,
    case
      when nullif(btrim(coalesce(p_search,'')),'') is null then 0
      when lower(coalesce(v.caption,''))=lower(btrim(p_search)) then 30
      when coalesce(v.caption,'') ilike '%'||btrim(p_search)||'%' then 18
      when coalesce(v.username,'') ilike '%'||btrim(p_search)||'%' then 12
      when coalesce(v.category,'') ilike '%'||btrim(p_search)||'%' then 10
      when array_to_string(coalesce(v.tags,'{}'::text[]),' ') ilike '%'||btrim(p_search)||'%' then 9
      else 0
    end::numeric as search_score
  from public.videos v
  left join lateral (
    select
      count(*) filter(where i.surface='short_feed') as short_impressions,
      count(*) filter(where i.surface='long_feed') as long_impressions
    from private.video_feed_impressions i
    where i.video_id=v.id
  ) im on true
  left join lateral (
    select
      count(distinct e.viewer_id) filter(where e.event_type='complete') as complete_users,
      count(distinct e.viewer_id) filter(where e.event_type='skip') as skip_users,
      count(distinct e.viewer_id) filter(where e.event_type='not_interested') as not_interested_users,
      count(distinct e.viewer_id) filter(where e.event_type='report') as report_users,
      count(distinct e.viewer_id) filter(where e.event_type='share') as share_users,
      count(distinct e.viewer_id) filter(where e.event_type='rewatch') as rewatch_users,
      coalesce(sum(e.watch_seconds) filter(where e.event_type='watch'),0)::numeric
        /nullif(count(distinct e.viewer_id) filter(where e.event_type='watch'),0) as avg_watch_seconds
    from private.video_engagement_events e
    where e.video_id=v.id and e.created_at>now()-interval '30 days'
  ) eg on true
  left join lateral (
    select count(distinct l.user_id) as unique_likes
    from public.likes l
    where l.video_id=v.id::text and l.user_id<>v.user_id
      and l.created_at>now()-interval '30 days'
  ) lk on true
  left join lateral (
    select count(distinct c.user_id) as unique_comments
    from public.video_comments c
    where c.video_id=v.id::text and c.user_id is not null
      and c.user_id<>v.user_id and c.created_at>now()-interval '30 days'
  ) cm on true
  left join private.video_distribution_state ds on ds.video_id=v.id
  where coalesce(v.processing_status,'published')='published'
    and coalesce(v.visibility,'public')='public'
    and coalesce(v.audience_type,'all')='all'
    and (
      not exists(select 1 from public.profiles p where p.user_id=v.user_id and p.is_private=true)
      or exists(select 1 from public.followers f where f.follower_id=auth.uid()::text and f.following_id=v.user_id)
    )
    and (
      p_video_type is null or
      (case
        when v.duration_seconds is not null then case when v.duration_seconds<=60 then 'short' else 'long' end
        else coalesce(v.video_type,case when v.width is not null and v.height is not null and v.width>v.height then 'long' else 'short' end)
      end)=p_video_type
    )
    and (nullif(btrim(coalesce(p_category,'')),'') is null or lower(coalesce(v.category,'')) like '%'||lower(btrim(p_category))||'%')
    and (
      nullif(btrim(coalesce(p_search,'')),'') is not null
      or (case
        when v.duration_seconds is not null then case when v.duration_seconds<=60 then 'short' else 'long' end
        else coalesce(v.video_type,case when v.width is not null and v.height is not null and v.width>v.height then 'long' else 'short' end)
      end)<>'short'
      or coalesce(ds.status,'active')='active'
    )
    and (
      nullif(btrim(coalesce(p_search,'')),'') is not null
      or auth.uid() is null
      or not exists(
        select 1 from private.video_feed_impressions seen
        where seen.video_id=v.id and seen.viewer_id=auth.uid()
          and seen.surface=case
            when (case
              when v.duration_seconds is not null then case when v.duration_seconds<=60 then 'short' else 'long' end
              else coalesce(v.video_type,case when v.width is not null and v.height is not null and v.width>v.height then 'long' else 'short' end)
            end)='short' then 'short_feed' else 'long_feed' end
      )
    )
    and not exists(
      select 1 from private.video_engagement_events h
      where h.video_id=v.id and h.viewer_id=auth.uid()
        and h.event_type='not_interested' and h.created_at>now()-interval '30 days'
    )
)
select c.id,c.video_url,c.caption,c.category,c.username,c.likes_count,c.views_count,c.created_at,
  c.user_id,c.affiliate_link,c.audience_type,c.duration_seconds,c.width,c.height,c.thumbnail_url,
  c.resolved_video_type,(c.rank_score+c.search_score)::numeric as score
from candidates c
order by (c.rank_score+c.search_score) desc,c.created_at desc
limit greatest(1,least(coalesce(p_limit,30),50))
offset greatest(0,least(coalesce(p_offset,0),5000));
$function$

revoke all on function public.get_recommended_video_feed_v2(integer,integer,text,text,text) from public;
grant execute on function public.get_recommended_video_feed_v2(integer,integer,text,text,text) to anon,authenticated;
