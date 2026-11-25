create table if not exists public.new_star_search_documents (
  id uuid primary key default gen_random_uuid(),
  url text not null unique,
  title text not null,
  source text not null,
  content text not null,
  language text not null default 'auto',
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  search_vector tsvector generated always as (
    setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(source, '')), 'B') ||
    setweight(to_tsvector('simple', coalesce(content, '')), 'C')
  ) stored,
  constraint new_star_search_documents_url_https check (url ~ '^https://'),
  constraint new_star_search_documents_title_length check (char_length(title) between 1 and 300),
  constraint new_star_search_documents_content_length check (char_length(content) between 1 and 30000),
  constraint new_star_search_documents_source_length check (char_length(source) between 1 and 200),
  constraint new_star_search_documents_language_length check (char_length(language) between 2 and 20)
);

create index if not exists new_star_search_documents_vector_idx
  on public.new_star_search_documents using gin (search_vector);
create index if not exists new_star_search_documents_updated_idx
  on public.new_star_search_documents (updated_at desc);

alter table public.new_star_search_documents enable row level security;
revoke all on public.new_star_search_documents from anon, authenticated;
grant select, insert, update, delete on public.new_star_search_documents to service_role;

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
    from public.new_star_search_documents d
    cross join input
    where char_length(trim(coalesce(p_query, ''))) > 0
      and d.search_vector @@ input.query
      and (
        coalesce(p_language, 'auto') = 'auto'
        or d.language = 'auto'
        or lower(d.language) = lower(p_language)
      )
    order by rank desc, d.updated_at desc
    limit greatest(1, least(coalesce(p_limit, 5), 10))
  )
  select ranked.title, ranked.url, ranked.snippet, ranked.source, ranked.published_at
  from ranked;
$$;

revoke all on function public.search_new_star_private_index(text, integer, text) from public, anon, authenticated;
grant execute on function public.search_new_star_private_index(text, integer, text) to service_role;
