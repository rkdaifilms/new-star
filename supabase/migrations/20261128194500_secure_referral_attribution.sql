-- Secure one-time referral attribution captured from Supabase Auth signup metadata.
create table if not exists public.referral_attributions (
    referred_user_id uuid primary key references auth.users(id) on delete cascade,
    referrer_user_id uuid not null references auth.users(id) on delete restrict,
    created_at timestamptz not null default now(),
    constraint referral_attributions_no_self_referral
        check (referred_user_id <> referrer_user_id)
);

create index if not exists referral_attributions_referrer_created_idx
    on public.referral_attributions (referrer_user_id, created_at desc);

alter table public.referral_attributions enable row level security;
revoke all on table public.referral_attributions from public, anon, authenticated;

create or replace function public.capture_signup_referral()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    raw_referrer text;
    parsed_referrer uuid;
begin
    raw_referrer := new.raw_user_meta_data ->> 'referrer_id';
    if raw_referrer is null or raw_referrer !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        return new;
    end if;

    parsed_referrer := raw_referrer::uuid;
    if parsed_referrer = new.id then
        return new;
    end if;

    if exists (select 1 from auth.users u where u.id = parsed_referrer) then
        insert into public.referral_attributions (referred_user_id, referrer_user_id)
        values (new.id, parsed_referrer)
        on conflict (referred_user_id) do nothing;
    end if;

    return new;
end;
$$;

drop trigger if exists capture_signup_referral_after_auth_user_insert on auth.users;
create trigger capture_signup_referral_after_auth_user_insert
after insert on auth.users
for each row execute function public.capture_signup_referral();

create or replace function public.get_my_referral_stats()
returns jsonb
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select jsonb_build_object(
        'referral_count',
        count(*)::bigint
    )
    from public.referral_attributions
    where referrer_user_id = auth.uid();
$$;

revoke all on function public.get_my_referral_stats() from public, anon;
grant execute on function public.get_my_referral_stats() to authenticated;

create or replace function public.record_referral_attribution(p_referrer_id uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    current_user_id uuid := auth.uid();
begin
    if current_user_id is null or p_referrer_id is null or current_user_id = p_referrer_id then
        return false;
    end if;
    if not exists (select 1 from auth.users u where u.id = p_referrer_id) then
        return false;
    end if;

    insert into public.referral_attributions (referred_user_id, referrer_user_id)
    values (current_user_id, p_referrer_id)
    on conflict (referred_user_id) do nothing;
    return found;
end;
$$;

revoke all on function public.record_referral_attribution(uuid) from public, anon;
grant execute on function public.record_referral_attribution(uuid) to authenticated;
