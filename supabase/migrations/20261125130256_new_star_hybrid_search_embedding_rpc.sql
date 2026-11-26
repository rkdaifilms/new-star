drop function if exists public.search_new_star_private_index(text, integer, text);

create or replace function public.search_new_star_private_index(
  p_query text,
  p_limit integer default 5,
  p_language text default 'auto',
  p_embedding vector(384) default null
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
  lexical_candidates as (
    select
      d.id, d.title, d.url, d.content, d.source, d.published_at,
      least(1.0, ts_rank_cd(d.search_vector, input.query) * 5.0) as lexical_score,
      0.0::double precision as semantic_score
    from public.new_star_search_chunks d
    cross join input
    where char_length(trim(coalesce(p_query, ''))) > 0
      and d.search_vector @@ input.query
      and (
        coalesce(p_language, 'auto') = 'auto'
        or d.language = 'auto'
        or lower(d.language) = lower(p_language)
      )
    order by ts_rank_cd(d.search_vector, input.query) desc, d.created_at desc
    limit greatest(1, least(coalesce(p_limit, 5), 10)) * 4
  ),
  semantic_candidates as (
    select
      d.id, d.title, d.url, d.content, d.source, d.published_at,
      0.0::double precision as lexical_score,
      greatest(0.0, 1.0 - (d.embedding OPERATOR(extensions.<=>) p_embedding)) as semantic_score
    from public.new_star_search_chunks d
    where p_embedding is not null
      and (
        coalesce(p_language, 'auto') = 'auto'
        or d.language = 'auto'
        or lower(d.language) = lower(p_language)
      )
    order by d.embedding OPERATOR(extensions.<=>) p_embedding
    limit greatest(1, least(coalesce(p_limit, 5), 10)) * 4
  ),
  candidates as (
    select * from lexical_candidates
    union all
    select * from semantic_candidates
  ),
  ranked as (
    select
      c.id, c.title, c.url, c.content, c.source, c.published_at,
      max(c.lexical_score) as lexical_score,
      max(c.semantic_score) as semantic_score
    from candidates c
    group by c.id, c.title, c.url, c.content, c.source, c.published_at
  )
  select
    ranked.title,
    ranked.url,
    left(ranked.content, 1200) as snippet,
    ranked.source,
    ranked.published_at
  from ranked
  order by (ranked.lexical_score * 0.6 + ranked.semantic_score * 0.4) desc
  limit greatest(1, least(coalesce(p_limit, 5), 10));
$$;

revoke all on function public.search_new_star_private_index(text, integer, text, vector) from public, anon, authenticated;
grant execute on function public.search_new_star_private_index(text, integer, text, vector) to service_role;
