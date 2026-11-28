do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'live_room_invites'
  ) then
    alter publication supabase_realtime add table public.live_room_invites;
  end if;
end
$$;
