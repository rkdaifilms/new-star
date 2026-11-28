create table if not exists public.live_sfu_sessions (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.live_rooms(id) on delete cascade,
  user_id uuid not null,
  session_id text not null unique,
  role text not null check (role in ('publisher','subscriber')),
  status text not null default 'active' check (status in ('active','closed','failed')),
  published_tracks jsonb not null default '[]'::jsonb,
  subscribed_tracks jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists live_sfu_sessions_room_active_idx
  on public.live_sfu_sessions(room_id, role, created_at desc) where status='active';
create index if not exists live_sfu_sessions_user_active_idx
  on public.live_sfu_sessions(user_id, room_id, role) where status='active';
alter table public.live_sfu_sessions enable row level security;
revoke all on public.live_sfu_sessions from anon, authenticated;
grant all on public.live_sfu_sessions to service_role;