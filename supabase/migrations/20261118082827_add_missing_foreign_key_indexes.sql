-- Adds indexes for foreign-key columns reported by Supabase performance advisors.
-- Safe to re-run: all indexes use IF NOT EXISTS.

create index if not exists admin_audit_logs_actor_id_idx on private.admin_audit_logs(actor_id);
create index if not exists commission_rules_updated_by_idx on private.commission_rules(updated_by);
create index if not exists creator_watch_daily_video_id_idx on private.creator_watch_daily(video_id);
create index if not exists creator_watch_daily_viewer_id_idx on private.creator_watch_daily(viewer_id);
create index if not exists payment_provider_events_payment_order_id_idx on private.payment_provider_events(payment_order_id);
create index if not exists star_platform_revenue_ledger_owner_id_idx on private.star_platform_revenue_ledger(owner_id);
create index if not exists video_view_dedupe_viewer_id_idx on private.video_view_dedupe(viewer_id);
create index if not exists video_watch_sessions_viewer_id_idx on private.video_watch_sessions(viewer_id);
create index if not exists creator_monetization_applications_reviewer_id_idx on public.creator_monetization_applications(reviewer_id);
create index if not exists messages_sender_id_idx on public.messages(sender_id);
create index if not exists payment_orders_package_id_idx on public.payment_orders(package_id);
create index if not exists shop_orders_payment_order_id_idx on public.shop_orders(payment_order_id);
create index if not exists shop_orders_product_id_idx on public.shop_orders(product_id);
create index if not exists star_campaign_events_promotion_id_idx on public.star_campaign_events(promotion_id);
