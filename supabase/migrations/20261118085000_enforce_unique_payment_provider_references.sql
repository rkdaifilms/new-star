-- Prevent one provider transaction/reference from being attached to multiple orders.
-- Empty placeholders are excluded; verified references must be non-empty in the credit RPC.
create unique index if not exists payment_orders_provider_reference_unique_idx
on public.payment_orders (provider, provider_reference)
where provider_reference is not null and btrim(provider_reference) <> '';