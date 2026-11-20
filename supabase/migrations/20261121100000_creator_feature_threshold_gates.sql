-- New Star creator feature gates (2026-11-21)
-- Video posting remains available from signup. Gated tools use verified follower counts.
create or replace function public.creator_has_1000_followers()
returns boolean language plpgsql stable security definer set search_path to ''
as $function$
declare v_creator uuid := auth.uid(); v_followers bigint := 0;
begin
  if v_creator is null then return false; end if;
  select count(distinct f.follower_id)::bigint into v_followers
  from public.followers f join auth.users u on u.id::text=f.follower_id
  where f.following_id=v_creator::text and f.follower_id<>v_creator::text;
  return v_followers>=1000;
end;
$function$;

create or replace function public.get_creator_feature_access()
returns jsonb language plpgsql stable security definer set search_path to ''
as $function$
declare
  v_creator uuid := auth.uid();
  v_progress jsonb;
  v_followers bigint := 0;
  v_approved boolean := false;
  v_long boolean := false;
  v_short boolean := false;
  v_video_eligible boolean := false;
begin
  if v_creator is null then raise exception 'Login required'; end if;
  select count(distinct f.follower_id)::bigint into v_followers
  from public.followers f join auth.users u on u.id::text=f.follower_id
  where f.following_id=v_creator::text and f.follower_id<>v_creator::text;
  v_progress := public.get_creator_monetization_progress();
  select exists(select 1 from public.creator_monetization_applications a
    where a.creator_id=v_creator and a.status='approved') into v_approved;
  v_long := coalesce((v_progress->>'watch_time_eligible')::boolean,false);
  v_short := coalesce((v_progress->>'shorts_eligible')::boolean,false);
  v_video_eligible := v_followers>=5000 and (v_long or v_short)
    and coalesce((v_progress->>'age_18_plus_verified')::boolean,false)
    and coalesce((v_progress->>'contact_verified')::boolean,false);
  return jsonb_build_object(
    'follower_count',v_followers,
    'live_access',v_followers>=1000,
    'live_gifts_access',v_followers>=1000,
    'live_gaming_access',v_followers>=1000,
    'sponsorship_access',v_followers>=1000,
    'campaign_create_access',v_followers>=1000,
    'campaign_join_access',v_followers>=1000,
    'long_video_monetization_access',v_approved and v_long,
    'short_video_monetization_access',v_approved and v_short,
    'video_gifts_access',v_approved and v_video_eligible,
    'ads_monetization_access',v_video_eligible,
    'monetization_approved',v_approved,
    'video_gifts_unlock_requires_approved_video_monetization',true
  );
end;
$function$;

revoke all on function public.creator_has_1000_followers() from public,anon;
grant execute on function public.creator_has_1000_followers() to authenticated;
revoke all on function public.get_creator_feature_access() from public,anon;
grant execute on function public.get_creator_feature_access() to authenticated;

drop policy if exists "Owners can create draft Star campaigns" on public.star_campaigns;
create policy "Owners with 1000 followers can create draft Star campaigns"
on public.star_campaigns for insert to authenticated
with check ((select auth.uid())=owner_id and status='draft'
  and payment_status='unpaid' and platform_fee_minor=0
  and (select public.creator_has_1000_followers()));

drop policy if exists "Creators can apply to paid active campaigns" on public.star_campaign_applications;
create policy "Creators with 1000 followers can apply to paid active campaigns"
on public.star_campaign_applications for insert to authenticated
with check ((select auth.uid())=creator_id and status='pending'
  and (select public.creator_has_1000_followers())
  and exists(select 1 from public.star_campaigns c
    where c.id=star_campaign_applications.campaign_id
      and c.status='active' and c.payment_status='paid'));

drop policy if exists "live_rooms_insert_own" on public.live_rooms;
create policy "live_rooms_insert_own_after_1000_followers"
on public.live_rooms for insert to authenticated
with check (creator_id=(select auth.uid())::text
  and (select public.creator_has_1000_followers()));
