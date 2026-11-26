create or replace function public.replace_new_star_search_chunks(p_chunks jsonb)
returns integer
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
  affected integer;
begin
  if p_chunks is null or jsonb_typeof(p_chunks) is distinct from 'array' then
    raise exception 'p_chunks must be a JSON array';
  end if;
  if jsonb_array_length(p_chunks) < 1 or jsonb_array_length(p_chunks) > 200 then
    raise exception 'A replacement batch must contain between 1 and 200 chunks';
  end if;

  if exists (
    select 1
    from jsonb_to_recordset(p_chunks) as x(url text, chunk_index integer)
    group by x.url, x.chunk_index
    having count(*) > 1
  ) then
    raise exception 'Duplicate URL and chunk_index pair in replacement batch';
  end if;

  if exists (
    select 1
    from jsonb_to_recordset(p_chunks) as x(
      url text, title text, source text, content text,
      embedding jsonb, language text, published_at timestamptz, chunk_index integer
    )
    where x.url is null or x.title is null or x.source is null or x.content is null
       or x.embedding is null
       or case when jsonb_typeof(x.embedding) = 'array'
          then jsonb_array_length(x.embedding) <> 384
          else true
       end
       or x.language is null or x.chunk_index is null or x.chunk_index < 0
  ) then
    raise exception 'One or more search chunks have missing or invalid fields';
  end if;

  if exists (
    select 1
    from jsonb_to_recordset(p_chunks) as x(embedding jsonb)
    cross join lateral jsonb_array_elements(x.embedding) as item(value)
    where jsonb_typeof(item.value) <> 'number'
  ) then
    raise exception 'Embedding vectors must contain only numeric values';
  end if;

  delete from public.new_star_search_chunks d
  using (
    select distinct x.url
    from jsonb_to_recordset(p_chunks) as x(url text)
  ) incoming
  where d.url = incoming.url;

  insert into public.new_star_search_chunks
    (url, title, source, content, embedding, language, published_at, chunk_index)
  select
    x.url, x.title, x.source, x.content,
    x.embedding::text::extensions.vector(384),
    x.language, x.published_at, x.chunk_index
  from jsonb_to_recordset(p_chunks) as x(
    url text, title text, source text, content text,
    embedding jsonb, language text, published_at timestamptz, chunk_index integer
  );

  get diagnostics affected = row_count;
  return affected;
end;
$$;

revoke all on function public.replace_new_star_search_chunks(jsonb) from public, anon, authenticated;
grant execute on function public.replace_new_star_search_chunks(jsonb) to service_role;
