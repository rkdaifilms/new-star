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

## Gift commission rules (verified database state)

There are exactly **three** gift products in the app:

1. **Video Gifts** — gifts sent to a creator's published video.
2. **LIVE Gifts** — gifts during ordinary LIVE hosting or a LIVE battle. Hosting and battle share one system and one commission rule.
3. **LIVE Gaming Gifts** — gifts during a creator's live gameplay screen stream.

All three use the same configured split: **50% platform / 50% creator**. All three rules remain **draft** until the server routes are implemented and verified. A LIVE battle is not a fourth gift product and must never use a separate battle commission.

Verified configuration:
- `video_gifts`: 5,000 basis points (50%), **draft**.
- `live_gifts`: 5,000 basis points (50%), **draft**; no LIVE hosting/battle gift route is active.
- `gaming_stream_gifts`: 5,000 basis points (50%), **draft**.
- The legacy `gaming_battle` rule is **retired** so it cannot become a separate battle-gift commission.
- `affiliate`: 2,000 basis points, draft.
- `campaign`: 2,500 basis points, draft.
- `sponsorship`: 2,000 basis points, draft.
- `ads`: 5,500 basis points, draft.

Draft values are configuration only. They must not be treated as payable earnings or used to move money. The gift send routes remain unavailable until a secure server-authorized transaction is implemented and verified. Do not expose service-role functions to browsers or trust client-supplied sender IDs, recipient IDs, gift prices, or commission percentages.

The existing `public.send_creator_gift_secure` function is a **video-gift-specific** service-role-only function. It is not a shared implementation for LIVE hosting/battles or LIVE gaming. Its caller wiring has not been verified; therefore the Video Gifts UI must not claim that gifts can be sent.

LIVE Hosting and LIVE Battle must call the same LIVE gift transaction path and use `live_gifts`. LIVE Gaming must use its own `gaming_stream_gifts` context. The server must determine context from the authenticated room/stream record, not a client-provided commission value.

PK matchmaking and score are separate from gifts. No paid entry, coin staking, wagering, or coin-prize pool is part of this gift design.

## Gift wallet-ledger consistency fix

- Updated `public.send_creator_gift_secure` so a successful gift also writes the sender debit to `public.wallet_ledger` as `gift_spend`, using the request UUID as a stable idempotency reference.
- The wallet deduction, spend-ledger row, gift transaction, creator earning row, and platform coin ledger are in the same database transaction. A ledger conflict aborts and rolls back the whole gift transaction.
- Verified the function remains `SECURITY DEFINER`, checks the `video_gifts` rule, and is executable only by `postgres` and `service_role`; `anon` and `authenticated` have no execute grant.
- The `video_gifts` commission rule remains `draft`, and the database currently has zero wallet-ledger, gift-transaction, and creator-gift-earning rows. Therefore this does **not** activate gifts or create any balances.
- The app/server caller is still not verified. Do not expose this service-role-only function to a browser or pass a client-supplied sender ID without server-side authentication binding.

## Post-change security scan

- Supabase security advisors were rerun after the gift-ledger change. The new service-role-only gift function did not appear as a public or authenticated executable SECURITY DEFINER finding.
- Existing advisor warnings remain for the video feed and creator/watch-session SECURITY DEFINER RPCs. Their function bodies were reviewed for authenticated-user checks where relevant; the public video-feed RPC is intentionally public-facing but should receive a separate focused authorization/privacy review before unrelated ACL changes.
- The advisor also reports leaked-password protection is disabled. This is an Auth project setting and remains a separate administrator configuration item.
- These findings are recorded rather than silently changing video-feed, watch-time, or authentication behavior during the wallet repair.

## Sponsorship and campaign accounting audit

- The sponsorship commission rule is stored as 2,000 basis points (20% platform / 80% creator), **draft**.
- A read-only database inventory found no sponsorship-specific earning ledger table or sponsorship commission function. The existing campaign tables record campaign/application/event data; they do not by themselves prove a creator payment or platform commission was calculated.
- Therefore, the 20/80 value is configuration only. Do not display it as earned money, credit creator balances, or initiate a payout from a client-side calculation.
- Before sponsorship accounting can be enabled, an eligible adult-managed business owner must approve the contract terms and an authorized server-side route must record the sponsor payment, agreed eligible cost deductions, platform fee, creator share, refunds/adjustments, and an auditable idempotency reference. This remains pending and must be tested without real funds first.

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
