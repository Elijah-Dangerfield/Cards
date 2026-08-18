# CARDS-8V — the iOS App Store build sells nothing: StoreKit doesn't recognize any of the 3 chip packs

**Sentry:** [CARDS-8V](https://elijah-dangerfield.sentry.io/issues/CARDS-8V) · level `error`,
`logger_tag=ProductsRepository` · 6 events since 2026-07-09, status `unresolved / regressed`, last
seen **2026-08-12T02:37:38Z**.

> Store did not recognize 3/3 chip-pack SKU(s); hiding from shop:
> `[com.cards.iap.chips.small, com.cards.iap.chips.medium, com.cards.iap.chips.large]`.
> Check the store listing (missing / not approved / region-blocked).

## The 2026-07-10 disposition is stale

This was dispositioned twice as dev/pre-launch store-listing noise — by `feedback-triage` on
2026-07-10, and again in this ledger as `CARDS-96` ("dev store-listing noise"). That was correct
*then*. It is not correct now: every event in the current window is on the **public iOS release**.

| | |
|---|---|
| release | `cards@0.1.0+3`, dist `202607231404`, `commit_sha 36aa3153f4ab` (= tag `v0.1.0`) |
| environment | `store-ios-release` — `releaseChannel=store`, i.e. App Store, not TestFlight `beta` |
| devices | `iPhone17,2` on iOS 26.5.2 (2026-07-23) and iOS 26.6.1 (2026-08-12) |
| installs | `03a4e6ee-…` and `ad8cc889-…` — two distinct real users |

Confirmed independently in Loki
(`{service_name="cards-client", deployment_environment="prod"} | platform="ios"`): install
`ad8cc889` logged the line twice at ERROR during its 2026-08-12 first launch, then completed
onboarding normally. The user is real, onboarded, and structurally unable to buy anything.

## What it means

`ProductsRepositoryImpl.doRefresh`
(`libraries/products/impl/src/commonMain/.../ProductsRepositoryImpl.kt:179-205`) fetches the server
catalog, queries the store for each pack's platform SKU, and `reconcileAgainst` **drops any pack the
store didn't return**. When the store answered authoritatively and everything got dropped, it logs
the line above at ERROR on purpose — the code comment says it exists "so the mismatch is visible
before users report 'the shop is empty'".

It was visible. Nothing acted on it for three weeks.

- Server catalog ships iOS SKU `com.cards.iap.chips.small` / `.medium` / `.large` (Android uses
  `chips_small` etc.) — see `PostgresProductCatalogSourceTest` / `ProductsRoutesTest`.
- StoreKit returned none of the three on a store-signed release build. That is an **App Store
  Connect side** condition: the in-app purchases don't exist under those product ids, aren't
  approved, aren't attached to the app version, the Paid Apps agreement / tax & banking isn't
  active, or they're region-blocked (one of these users is `en_SG`).
- Net effect: **iOS revenue is zero by construction**, and the shop shows the user an empty
  chip-pack section with no explanation.

## Why nothing escalated it

This is the same blind spot as the standing 2026-07-15 note on A5. `A5 · purchase success rate low`
divides completions by attempts. If no pack is ever *shown*, there are no attempts, no
`purchase.initiated`, no `purchase.failed` — so A5 reads healthy while the store sells nothing. The
one signal that does exist (this ERROR line) has no alert rule, no dashboard panel, and no
structured `event_name`, so it only surfaces if a human happens to be reading Sentry.

`dc-revenue` showing $0 is indistinguishable from "nobody bought anything today".

## Fix direction

Two owners, deliberately split:

- **Human / App Store Connect (not worker-pickable).** Create-or-approve the three IAP products
  under exactly `com.cards.iap.chips.{small,medium,large}`, attach them to the live version, and
  confirm the Paid Applications agreement + tax/banking are active. → appended to
  `docs/developer-todo.md` under Launch readiness. Nothing in the repo can fix this.
- **Engineering (ENG-43).** Make the condition impossible to miss again: emit it as a first-class
  structured event (e.g. `shop.catalog_skus_dropped` with `dropped`/`total`/`platform`) instead of
  a bare ERROR string, alert on it (an A8 rule, or a `dc-revenue`/`dc-billing-health` panel), and
  give the shop an honest state when every pack is gone rather than a silently empty section.

## Disposition

todo **ENG-43 `[P1]`** (2026-08-18) for the engineering half + a `developer-todo.md` line for the
ASC half. Sentry issue left **unresolved** (nothing is fixed yet) with a triage comment. Prior
`CARDS-96` / 2026-07-10 "dev noise" disposition is superseded by this file. P1 rather than P0
because the money unblock is the human ASC item; ENG-43 is the visibility work so this can't sit
invisible for three weeks a second time.
