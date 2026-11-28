create or replace function public.live_pk_matchmake(p_user_id uuid, p_room_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_existing public.live_pk_queue%rowtype;
  v_opponent public.live_pk_queue%rowtype;
  v_match_id uuid;
begin
  perform pg_advisory_xact_lock(72190411);

  if not exists (
    select 1 from public.live_rooms
    where id = p_room_id and creator_id = p_user_id::text and is_live = true
  ) then
    raise exception 'An active LIVE room owned by this creator is required';
  end if;

  update public.live_pk_queue q
     set status = 'cancelled', updated_at = now()
   where q.status = 'waiting'
     and (
       q.updated_at < now() - interval '10 minutes'
       or not exists (
         select 1 from public.live_rooms r
          where r.id = q.room_id and r.creator_id = q.user_id::text and r.is_live = true
       )
     );

  select * into v_existing from public.live_pk_queue
   where user_id = p_user_id and status in ('waiting','matched')
   order by created_at desc limit 1 for update;
  if found then
    if v_existing.status = 'matched' and v_existing.match_id is not null then
      return jsonb_build_object('status','matched','match_id',v_existing.match_id);
    end if;
    update public.live_pk_queue set updated_at = now() where id = v_existing.id;
    return jsonb_build_object('status','waiting');
  end if;

  select * into v_opponent from public.live_pk_queue
   where status = 'waiting' and user_id <> p_user_id and room_id <> p_room_id
   order by created_at asc limit 1 for update skip locked;

  if not found then
    insert into public.live_pk_queue(user_id, room_id, status, created_at, updated_at)
    values (p_user_id, p_room_id, 'waiting', now(), now());
    return jsonb_build_object('status','waiting');
  end if;

  if exists (
    select 1 from public.live_pk_matches
    where status='active' and (
      creator_a_id in (p_user_id, v_opponent.user_id)
      or creator_b_id in (p_user_id, v_opponent.user_id)
    )
  ) then
    raise exception 'One of these creators is already in an active battle';
  end if;

  insert into public.live_pk_matches(room_a_id, room_b_id, creator_a_id, creator_b_id, status, started_at, created_at)
  values (v_opponent.room_id, p_room_id, v_opponent.user_id, p_user_id, 'active', now(), now())
  returning id into v_match_id;

  update public.live_pk_queue
    set status='matched', match_id=v_match_id, updated_at=now()
    where id=v_opponent.id;
  insert into public.live_pk_queue(user_id, room_id, status, match_id, created_at, updated_at)
    values (p_user_id, p_room_id, 'matched', v_match_id, now(), now());

  return jsonb_build_object('status','matched','match_id',v_match_id);
end;
$$;

revoke all on function public.live_pk_matchmake(uuid,uuid) from public, anon, authenticated;
grant execute on function public.live_pk_matchmake(uuid,uuid) to service_role;