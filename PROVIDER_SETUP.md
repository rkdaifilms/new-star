# New Star — Production Provider Setup

This project must only be operated by a legally eligible adult or registered business. Do not bypass a provider's age, identity, business, tax, or country eligibility checks. Never send passwords, OTPs, bank details, or API secrets in chat, GitHub issues, frontend forms, or browser storage.

## Supabase Edge Function secrets

The project currently has an `admin-overview` Edge Function and a secure database function `complete_coin_purchase`. It does **not** yet have a deployed payment-initiation/verification Edge Function. Payment orders therefore cannot currently be created through a real provider checkout; coin packages remain drafts.

The reviewed payment integration will need these server-only configuration names:
- `KHALTI_SECRET_KEY`
- `KHALTI_MODE` (`sandbox` or `production`)
- `ESEWA_PRODUCT_CODE`
- `ESEWA_SECRET_KEY`
- `ESEWA_MODE` (`sandbox` or `production`)
- `APP_BASE_URL` (approved canonical production origin)

Supabase also supplies `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` to Edge Functions. Never expose the service-role key in frontend code.

Adding keys alone will not activate payments. A payment Edge Function must be implemented and deployed, callbacks/lookups verified server-side, packages explicitly approved, and sandbox tests passed.

## Payment ledger protections already applied

- `complete_coin_purchase` is callable only by the server `service_role`; anonymous and signed-in client users cannot credit coins directly.
- The database enforces a unique `(provider, provider_reference)` for non-empty provider transaction references, preventing one provider transaction from being attached to multiple orders.
- `payment_orders.idempotency_key` and `wallet_ledger.idempotency_key` are unique, and the coin-credit function locks the order before writing the ledger and wallet.
- The payment-order table currently has no orders. No live payment has been processed or simulated.

## Khalti Web Checkout

Official docs: https://docs.khalti.com/khalti-epayment/

Server creates a pending order from an active database package, initiates Khalti checkout, and redirects to the returned payment URL. Treat the browser callback as untrusted. Server must call Khalti lookup using `pidx`; only `Completed`, exact matching amount/order, and non-refunded state may credit coins. Use idempotency to prevent duplicate credit. A merchant account and production approval are required.

## eSewa ePay v2

Official docs: https://developer.esewa.com.np/pages/Epay

Server creates the pending order and HMAC-SHA256 signature. The signing secret stays server-side. On return, decode and verify the signed payload, then call eSewa transaction status verification. Only `COMPLETE` with exact product code, transaction UUID and total amount may credit coins. Use the provider reference and call `complete_coin_purchase` once. Use current official UAT/production endpoints and merchant-issued credentials; never use sample UAT credentials in production.

## Ad delivery: Web and Mobile are different

- **AdMob** is for native Android/iOS apps. It needs the correct Google Mobile Ads SDK, app/ad-unit IDs, app review and policy compliance. Saving IDs in this web dashboard does not make ads appear in a browser.
- **AdSense** is for websites. A web deployment needs site approval, publisher/ad-unit configuration, consent/privacy compliance and the official web ad code. AdMob IDs are not a substitute for AdSense.
- Ads must not display until Google approves the relevant site/app and ad units. Revenue is not guaranteed.

Official Google docs: https://developers.google.com/admob and https://support.google.com/adsense/

## Creator payouts

The existing `admin-overview` function deliberately rejects payout requests. The project does not yet have a complete provider payout API, recipient onboarding, eligibility checks, verified settlement, reconciliation, or refund/dispute handling. Do not enable payout routes merely because an account identifier was entered.

## Go-live checklist

- [ ] Adult-managed eligible merchant/business accounts approved by each provider
- [ ] Production credentials stored as Supabase Edge Function secrets
- [ ] Payment Edge Function implemented and deployed
- [ ] Khalti sandbox initiate → callback → lookup → exact amount match → one-time credit test passed
- [ ] eSewa UAT initiate → signed callback → status check → exact amount match → one-time credit test passed
- [ ] Refund, duplicate callback, timeout, cancellation and amount-mismatch tests passed
- [ ] Coin package publishing requires owner approval and a verified provider route
- [ ] Google approved the actual website/app and advertising units
- [ ] AdSense web tags or AdMob native SDK integrated in the correct platform
- [ ] Creator payout integration separately reviewed and tested
- [ ] Production smoke test and financial reconciliation completed

Until the relevant items are checked, show a clear “not configured / not approved” state. Never pretend payments or ads are active.
