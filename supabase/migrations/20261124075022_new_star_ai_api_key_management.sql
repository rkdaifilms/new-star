create table if not exists public.new_star_ai_api_keys (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  key_prefix text not null unique,
  key_hash text not null unique,
  scopes text[] not null default array['chat']::text[],
  rate_limit integer not null default 20 check (rate_limit between 1 and 120),
  created_at timestamptz not null default now(),
  last_used_at timestamptz,
  expires_at timestamptz,
  revoked_at timestamptz,
  constraint new_star_ai_api_keys_scopes_check
    check (cardinality(scopes) > 0 and scopes <@ array['chat','image','vision','transcribe','speak','research','status']::text[])
);

create index if not exists new_star_ai_api_keys_user_created_idx
  on public.new_star_ai_api_keys (user_id, created_at desc);

alter table public.new_star_ai_api_keys enable row level security;
revoke all on table public.new_star_ai_api_keys from public, anon, authenticated;
grant select, insert, update on table public.new_star_ai_api_keys to service_role;