-- Consolidate overlapping campaign SELECT policies without changing intended visibility.
-- Campaign applications: creators see their own; campaign owners see applications for their campaigns.
drop policy if exists "Campaign owners can read applications" on public.star_campaign_applications;
drop policy if exists "Creators can read their applications" on public.star_campaign_applications;
drop policy if exists "Creators and campaign owners can read applications" on public.star_campaign_applications;
create policy "Creators and campaign owners can read applications"
on public.star_campaign_applications
for select to authenticated
using (
  (select auth.uid()) = creator_id
  or exists (
    select 1
    from public.star_campaigns c
    where c.id = star_campaign_applications.campaign_id
      and c.owner_id = (select auth.uid())
  )
);

-- Campaigns: public can see only active, paid campaigns; authenticated owners can also see their own drafts.
drop policy if exists "Owners can read their Star campaigns" on public.star_campaigns;
drop policy if exists "Public can read active Star campaigns" on public.star_campaigns;
drop policy if exists "Public active paid campaigns and owner campaigns" on public.star_campaigns;
create policy "Public active paid campaigns and owner campaigns"
on public.star_campaigns
for select to anon, authenticated
using (
  (status = 'active' and payment_status = 'paid')
  or (
    (select auth.uid()) is not null
    and owner_id = (select auth.uid())
  )
);