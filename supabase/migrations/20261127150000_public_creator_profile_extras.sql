create table if not exists public.creator_profile_extras (
  user_id uuid primary key references auth.users(id) on delete cascade,
  cover_url text,
  cover_transform jsonb not null default '{}'::jsonb,
  website text,
  facebook text,
  instagram text,
  youtube text,
  tiktok text,
  updated_at timestamptz not null default now()
);

alter table public.creator_profile_extras enable row level security;

grant select, insert, update, delete on public.creator_profile_extras to authenticated;

drop policy if exists "Creator extras are visible to public profiles" on public.creator_profile_extras;
create policy "Creator extras are visible to public profiles"
on public.creator_profile_extras
for select
to authenticated
using (
  (select auth.uid()) = user_id
  or exists (
    select 1
    from public.profiles p
    where p.user_id = creator_profile_extras.user_id
      and coalesce(p.is_private, false) = false
  )
);

drop policy if exists "Creators insert their own extras" on public.creator_profile_extras;
create policy "Creators insert their own extras"
on public.creator_profile_extras
for insert
to authenticated
with check ((select auth.uid()) = user_id);

drop policy if exists "Creators update their own extras" on public.creator_profile_extras;
create policy "Creators update their own extras"
on public.creator_profile_extras
for update
to authenticated
using ((select auth.uid())::text = user_id)
with check ((select auth.uid())::text = user_id);

drop policy if exists "Creators delete their own extras" on public.creator_profile_extras;
create policy "Creators delete their own extras"
on public.creator_profile_extras
for delete
to authenticated
using ((select auth.uid())::text = user_id);

create index if not exists creator_profile_extras_user_id_idx
on public.creator_profile_extras(user_id);

notify pgrst, 'reload schema';
