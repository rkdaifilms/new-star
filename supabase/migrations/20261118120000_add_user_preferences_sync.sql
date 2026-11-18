create table if not exists public.user_preferences (
  user_id uuid primary key references auth.users(id) on delete cascade,
  preferences jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  constraint user_preferences_object check (jsonb_typeof(preferences) = 'object')
);
alter table public.user_preferences enable row level security;
revoke all on table public.user_preferences from anon;
grant select, insert, update, delete on table public.user_preferences to authenticated;
drop policy if exists "Users read own preferences" on public.user_preferences;
create policy "Users read own preferences" on public.user_preferences for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "Users insert own preferences" on public.user_preferences;
create policy "Users insert own preferences" on public.user_preferences for insert to authenticated with check ((select auth.uid()) = user_id);
drop policy if exists "Users update own preferences" on public.user_preferences;
create policy "Users update own preferences" on public.user_preferences for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "Users delete own preferences" on public.user_preferences;
create policy "Users delete own preferences" on public.user_preferences for delete to authenticated using ((select auth.uid()) = user_id);
create index if not exists user_preferences_updated_at_idx on public.user_preferences (updated_at desc);
