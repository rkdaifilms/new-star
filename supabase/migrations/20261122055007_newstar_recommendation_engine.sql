-- Private, deduplicated impressions and organic distribution state for video feeds.
create table if not exists private.video_feed_impressions (
  video_id uuid not null references public.videos(id) on delete cascade,
  viewer_id uuid not null,
  surface text not null check (surface in ('short_feed','long_feed','search')),
  stage_number integer not null default 0,
  shown_at timestamptz not null default now(),
  primary key (video_id, viewer_id, surface)
);
create index if not exists video_feed_impressions_stage_idx
  on private.video_feed_impressions(video_id, surface, stage_number, shown_at);

create table if not exists private.video_distribution_state (
  video_id uuid primary key references public.videos(id) on delete cascade,
  video_type text not null default 'short' check (video_type in ('short','long')),
  stage_number integer not null default 1,
  stage_target bigint not null default 200,
  status text not null default 'active' check (status in ('active','paused')),
  last_completion_rate numeric(8,5),
  last_like_rate numeric(8,5),
  last_comment_rate numeric(8,5),
  last_evaluated_at timestamptz,
  updated_at timestamptz not null default now()
);
