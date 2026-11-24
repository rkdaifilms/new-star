-- New Star multilingual semantic search storage.
-- Requires Supabase's pgvector extension. Apply through Supabase migrations.
create extension if not exists vector with schema extensions;

create table if not exists public.new_star_search_chunks (
  id bigint generated always as identity primary key,
  url text not null,
  title text not null,
  source text not null,
  content text not null,
  embedding extensions.vector(384) not null,
  created_at timestamptz not null default now()
);

create index if not exists new_star_search_chunks_embedding_hnsw
  on public.new_star_search_chunks
  using hnsw (embedding extensions.vector_cosine_ops)
  with (m = 16, ef_construction = 96);

create index if not exists new_star_search_chunks_source_idx
  on public.new_star_search_chunks (source);

create index if not exists new_star_search_chunks_url_idx
  on public.new_star_search_chunks (url);

alter table public.new_star_search_chunks enable row level security;
revoke all on public.new_star_search_chunks from anon, authenticated;
grant select, insert, update, delete on public.new_star_search_chunks to service_role;
grant usage, select on sequence public.new_star_search_chunks_id_seq to service_role;

comment on table public.new_star_search_chunks is
  'Private New Star semantic search chunks. Access only from trusted server-side service role.';
