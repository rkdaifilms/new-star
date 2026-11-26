create table if not exists private.ai_gateway_api_key_rate_limits (
  api_key_id uuid primary key references public.new_star_ai_api_keys(id) on delete cascade,
  window_started_at timestamptz not null,
  request_count integer not null default 0,
  updated_at timestamptz not null default now()
);

alter table private.ai_gateway_api_key_rate_limits enable row level security;

create or replace function private.consume_ai_gateway_api_key_rate_limit(
  p_api_key_id uuid,
  p_limit integer default 20
)
returns boolean
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_count integer;
  v_window timestamptz := date_trunc('minute', now());
begin
  if p_api_key_id is null or p_limit < 1 or p_limit > 100 then
    return false;
  end if;

  insert into private.ai_gateway_api_key_rate_limits(
    api_key_id, window_started_at, request_count, updated_at
  )
  values (p_api_key_id, v_window, 1, now())
  on conflict (api_key_id) do update
    set window_started_at = case
          when private.ai_gateway_api_key_rate_limits.window_started_at = v_window
            then private.ai_gateway_api_key_rate_limits.window_started_at
          else v_window
        end,
        request_count = case
          when private.ai_gateway_api_key_rate_limits.window_started_at = v_window
            then private.ai_gateway_api_key_rate_limits.request_count + 1
          else 1
        end,
        updated_at = now()
  returning request_count into v_count;

  return v_count <= p_limit;
end;
$function$;

create or replace function public.consume_ai_gateway_api_key_rate_limit(
  p_api_key_id uuid,
  p_limit integer
)
returns boolean
language sql
security definer
set search_path to ''
as $function$
  select private.consume_ai_gateway_api_key_rate_limit(p_api_key_id, p_limit);
$function$;

revoke all on function private.consume_ai_gateway_api_key_rate_limit(uuid, integer) from public, anon, authenticated, service_role;
revoke all on function public.consume_ai_gateway_api_key_rate_limit(uuid, integer) from public, anon, authenticated;
grant execute on function public.consume_ai_gateway_api_key_rate_limit(uuid, integer) to service_role;
