-- Add indexed PostgreSQL full-text retrieval for hybrid vector + lexical search.
alter table public.new_star_search_chunks
  add column if not exists search_vector tsvector
  generated always as (
    to_tsvector('simple'::regconfig, coalesce(title, '') || ' ' || coalesce(content, ''))
  ) stored;

create index if not exists new_star_search_chunks_search_vector_gin
  on public.new_star_search_chunks using gin (search_vector);

comment on column public.new_star_search_chunks.search_vector is
  'Generated Unicode-aware simple-config full-text document used for lexical candidate retrieval and reciprocal-rank fusion.';
