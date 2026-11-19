create table if not exists public.content_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references auth.users(id) on delete cascade,
  target_type text not null check (target_type in ('video','comment','user')),
  target_id text not null,
  reason text not null check (reason in ('copyright','harassment','spam','scam','nudity_or_sexual_content','violence','dangerous_content','child_safety','privacy','other')),
  details text,
  status text not null default 'pending' check (status in ('pending','reviewing','actioned','dismissed')),
  reviewed_by uuid references auth.users(id),
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  constraint content_reports_details_length check (details is null or char_length(details)<=1200)
);
create index if not exists content_reports_status_created_idx on public.content_reports(status,created_at desc);
create index if not exists content_reports_target_idx on public.content_reports(target_type,target_id);
alter table public.content_reports enable row level security;
drop policy if exists content_reports_submit_own on public.content_reports;
create policy content_reports_submit_own on public.content_reports for insert to authenticated with check (reporter_id=auth.uid());
drop policy if exists content_reports_read_own on public.content_reports;
create policy content_reports_read_own on public.content_reports for select to authenticated using (reporter_id=auth.uid());
revoke all on public.content_reports from anon, authenticated;
grant select,insert on public.content_reports to authenticated;
grant all on public.content_reports to service_role;
