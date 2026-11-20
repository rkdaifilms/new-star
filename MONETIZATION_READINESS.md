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

## Commission rules (verified database state)

- `private.commission_rules` has a unique `rule_code` and stores the platform share in basis points; 10,000 basis points means 100%.
- `live_gifts`: 5,000 basis points, **active** (50% platform / 50% creator).
- `video_gifts`: 5,000 basis points, **draft** (50% / 50% proposed; not enabled).
- `gaming_battle`: 2,500 basis points, **draft** (25% proposed; no paid entry, staking, or coin-prize pool).
- `affiliate`: 2,000 basis points, **draft** (20% proposed platform share of the eligible commission, not of the whole product sale).
- `campaign`: 2,500 basis points, **draft** (25% proposed service fee; campaign spend, creator reward, and taxes must remain separately accounted).
- `sponsorship`: 2,000 basis points, **draft** (20% platform / 80% creator for creator-sponsored campaigns; calculate the split on the agreed distributable sponsorship amount after explicitly agreed taxes, refunds, and pass-through campaign costs. A platform-only sponsorship must use a separate written contract and separate rule, not this creator-campaign default).
- `ads`: 5,500 basis points, **draft** (55% platform / 45% creator proposal; earnings require verified network reporting).
- Draft values are setup records only. They must not be treated as active payable earnings or used to move money until an eligible adult-managed account owner approves the commercial terms and the corresponding revenue route is implemented and tested.
- `public.send_creator_gift_secure` has been changed to read the `video_gifts` rule. Its caller wiring has not yet been verified, so do not assume that the app's video-gift UI is connected to it. It remains service-role-only; do not expose direct client execution.
- The live gift UI is explicitly preview-only: cards are non-clickable and the send handler fails closed without wallet deduction or a fake chat receipt. The live-gift transaction route is not yet verified end-to-end.
- Both live and video gifting must remain unavailable until their respective app routes, authenticated wallet reads, and server-authorized transaction calls are wired and tested end-to-end.

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
- [ ] Gift paths use the secure server function and prove the configured commission accounting for live and video gifts.
- [ ] AdMob Android SDK and AdSense web tags are integrated separately and approved.
- [ ] Payout onboarding and settlement are independently reviewed and tested.
- [ ] Production monitoring and financial reconciliation are enabled.

Until the relevant checks pass, show a clear setup-pending state. Never display a fake successful payment, coin credit, ad impression, earning, or payout.

## Native Android ads audit

- The Android wrapper's `android/app/build.gradle.kts` currently includes AndroidX and WebRTC dependencies only; the Google Mobile Ads SDK is not present.
- `android/app/src/main/AndroidManifest.xml` has no Google Mobile Ads application ID metadata.
- The current web monetization page contains status information for AdSense and AdMob, but this is not an ad SDK integration and does not generate impressions or earnings.
- Do not insert a guessed App ID or test/production ad unit. Native AdMob integration remains pending until the eligible publisher account's approved App ID and ad-unit IDs are supplied through a secure project configuration path and test ads are verified.
- The web wrapper must not attempt to use AdSense tags as a substitute for native AdMob inventory.
