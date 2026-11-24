-- Durable ownership and status for asynchronous Gemini video jobs.
create table if not exists public.ai_video_jobs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  provider_interaction_id text not null unique,
  provider_model text not null,
  status text not null default 'in_progress'
    check (status in ('in_progress', 'completed', 'failed')),
  storage_path text,
  failure_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists ai_video_jobs_user_created_idx
  on public.ai_video_jobs (user_id, created_at desc);

alter table public.ai_video_jobs enable row level security;
-- No client policies: only the Edge Function service role may read or mutate jobs.
