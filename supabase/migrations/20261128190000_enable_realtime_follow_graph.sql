do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'followers'
  ) then
    alter publication supabase_realtime add table public.followers;
  end if;

  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'follow_requests'
  ) then
    alter publication supabase_realtime add table public.follow_requests;
  end if;
end
$$;
