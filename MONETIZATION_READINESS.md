# New Star Monetization Readiness

Last reviewed: 2026-11-20

This document records verified implementation state. It is not a claim that live payments, ads, or payouts are enabled.

## Coin wallet and purchase flow

- The wallet page reads balances from `public.wallet_ledger`; it does not let the browser edit balances.
- The wallet page reads package records from `public.coin_packages` for the web channel.
- The current package catalogue has no published packages, so the page must show an empty/setup-pending state rather than invent prices.
- `public.complete_coin_purchase(uuid,text,bigint,text)` validates the provider-verified amount and currency against the order, records an idempotent ledger entry, credits the wallet, and marks the order paid.
- That function is restricted to `service_role`. Do not grant it to `anon` or `authenticated`.
- There is no deployed payment-initiation/verification Edge Function yet. Do not enable a Pay Now flow until a provider adapter, server-side verification, refund handling, and sandbox regression tests are complete.

## Gift commission

- The active `private.commission_rules` row for `live_gifts` was set to 5000 basis points (50% platform / 50% creator).
- `public.send_creator_gift_secure` reads this active `live_gifts` rule and records the sender deduction, creator net, platform fee, and platform ledger entry in one database transaction.
- The current secure gift function is service-role-only. Do not expose direct client execution.
- Video gift commission has not been separately verified as an independent route. If video gifts use this same secure function, they inherit the same 50/50 rule; otherwise, audit and route them through the same server-side accounting before enabling them.
- The live gift catalogue is now explicitly preview-only: cards are non-clickable, the modal states gifting is unavailable, and the send handler fails closed without a wallet deduction or fake chat receipt.
- It must remain preview-only until the authenticated wallet read path and server-authorized gift route are wired and tested end-to-end.

## Ads

- AdMob is the native mobile ad integration; AdSense is the web ad integration. They are not interchangeable.
- Do not load ads until the correct app/site and ad units have provider approval and the official SDK/tag is integrated for that platform.
- Ad revenue must come from verified provider reporting, not client-side counters.

## Creator payout

- Payout provider configuration is not active and the payout flow is not complete.
- Do not collect bank details, wallet identifiers, passwords, PINs, or OTPs in the app.
- Payout requires an eligible adult-managed business/creator account, provider onboarding, server-side recipient verification, settlement, reconciliation, and dispute/refund handling.

## Required go-live checks

- [ ] Adult-managed merchant and publisher accounts meet provider and local eligibility requirements.
- [ ] Payment initiation and provider lookup/callback verification are implemented server-side.
- [ ] Sandbox tests cover success, cancellation, timeout, duplicate callback, refund, and amount/currency mismatch.
- [ ] Coin packages have owner-approved prices, currency, region, and bonus policy.
- [ ] Gift path uses the secure server function and proves 50/50 accounting for both live and video gifts.
- [ ] AdMob Android SDK and AdSense web tags are integrated separately and approved.
- [ ] Payout onboarding and settlement are independently reviewed and tested.
- [ ] Production monitoring and financial reconciliation are enabled.

Until the relevant checks pass, show a clear setup-pending state. Never display a fake successful payment, coin credit, ad impression, earning, or payout.
