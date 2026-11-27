create or replace function public.get_public_creator_profile_extras(p_creator_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, auth, pg_temp
as $$
declare
  profile_is_private boolean;
  creator_metadata jsonb;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  select coalesce(p.is_private, true)
    into profile_is_private
  from public.creator_public_profiles p
  where p.user_id = p_creator_id;

  if not found or profile_is_private then
    return null;
  end if;

  select u.raw_user_meta_data
    into creator_metadata
  from auth.users u
  where u.id = p_creator_id;

  if creator_metadata is null then
    return '{}'::jsonb;
  end if;

  return jsonb_strip_nulls(jsonb_build_object(
    'cover_url', creator_metadata ->> 'cover_url',
    'cover_transform', creator_metadata -> 'cover_transform',
    'website', creator_metadata ->> 'website',
    'facebook', creator_metadata ->> 'facebook',
    'instagram', creator_metadata ->> 'instagram',
    'youtube', creator_metadata ->> 'youtube',
    'tiktok', creator_metadata ->> 'tiktok'
  ));
end;
$$;

revoke all on function public.get_public_creator_profile_extras(uuid) from public, anon;
grant execute on function public.get_public_creator_profile_extras(uuid) to authenticated;

notify pgrst, 'reload schema';
