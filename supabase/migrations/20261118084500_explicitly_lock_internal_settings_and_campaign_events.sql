-- Keep internal configuration and attribution events inaccessible to client roles.
-- These tables are accessed only by trusted server-side code/service_role.
revoke all privileges on table public.app_settings from public, anon, authenticated;
revoke all privileges on table public.star_campaign_events from public, anon, authenticated;
revoke all privileges on sequence public.star_campaign_events_id_seq from public, anon, authenticated;

grant all privileges on table public.app_settings to service_role;
grant all privileges on table public.star_campaign_events to service_role;
grant usage, select on sequence public.star_campaign_events_id_seq to service_role;