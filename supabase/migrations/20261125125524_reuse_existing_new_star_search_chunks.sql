drop function if exists public.search_new_star_private_index(text, integer, text);
drop table if exists public.new_star_search_documents;

alter table public.new_star_search_chunks
  add column if not exists language text not null default 'auto',
  add column if not exists published_at timestamptz,
  add column if not exists chunk_index integer not null default 0;

create unique index if not exists new_star_search_chunks_url_chunk_uidx
  on public.new_star_search_chunks (url, chunk_index);

create or replace function public.search_new_star_private_index(
  p_query text,
  p_limit integer default 5,
  p_language text default 'auto'
)
returns table (
  title text,
  url text,
  snippet text,
  source text,
  published_at timestamptz
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  with input as (
    select websearch_to_tsquery('simple', left(coalesce(p_query, ''), 500)) as query
  ),
  ranked as (
    select
      d.title,
      d.url,
      left(d.content, 1200) as snippet,
      d.source,
      d.published_at,
      ts_rank_cd(d.search_vector, input.query) as rank
    from public.new_star_search_chunks d
    cross join input
    where char_length(trim(coalesce(p_query, ''))) > 0
      and d.search_vector @@ input.query
      and (
        coalesce(p_language, 'auto') = 'auto'
        or d.language = 'auto'
        or lower(d.language) = lower(p_language)
      )
    order by rank desc, d.created_at desc, d.chunk_index asc
    limit greatest(1, least(coalesce(p_limit, 5), 10))
  )
  select ranked.title, ranked.url, ranked.snippet, ranked.source, ranked.published_at
  from ranked;
$$;

revoke all on function public.search_new_star_private_index(text, integer, text) from public, anon, authenticated;
grant execute on function public.search_new_star_private_index(text, integer, text) to service_role;
