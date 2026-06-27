# TODO

**Last reviewed:** 2026-06-27 (feedback triage) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## B. Auth & onboarding

_No open engineering items. (AUTH-9 — the Google browser-OAuth redesign to suspend-until-redirect, with link ≠ sign-in — shipped 2026-06-27, see [decisions.md](./decisions.md). End-to-end device QA + the Supabase dashboard config it depends on remain in [developer-todo.md](./developer-todo.md).)_

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md)._

---

## F. Shop & cosmetics

- `[P2]` **SHOP-3 — Host-chosen felt + card backs, shown to every player at the table.** Owner directive: let the game creator pick the felt and card backs from their inventory when creating a room, and have *every* player at the table see the host's chosen felt and card backs (incentivizes buying cosmetics). The host's selection already exists per-player; this makes it table-wide.
  **Acceptance:** create-room flow lets the host pick an owned felt + card back; the room snapshot carries them; all clients render the host's felt and card backs in-game. Ship a slice + a directional call on edge cases (host has none equipped → table default; whether a player's own equipped back still applies to their own cards) and let the reviewer course-correct.
  **Hints:** plumbing mirrors the "Player Card — Phase 2: opponent cosmetics over the wire" backlog item — put the host's equipped felt/back on the room/seat snapshot and read it at the play surface instead of the local `EquippedFelt` derived from the player's own inventory (`feltForProductId` in `PlayPokerViewModel`). Owner directive, Sentry [CARDS-4Q](https://elijah-dangerfield.sentry.io/issues/CARDS-4Q).

---

## G. Billing & IAP

Native IAP (Play Billing + StoreKit 2 + own server validation — no RevenueCat). The `BillingClient` abstraction, fake/dev clients, server wallet ledger, idempotent grant, the server-authoritative redeem endpoint, and the client-side validate->grant->reflect flow (behind `billing.realPurchasesEnabled`) already exist; these items fill the two remaining real gaps — the real platform store clients and the real receipt validators. Live store testing for several items is developer-gated on credentials/listings — those gates live in [developer-todo.md](./developer-todo.md); the code is buildable and unit/locally-testable now.

- `[P0]` **BILL-2 — Real receipt validators (Apple App Store Server API + Google Play Developer API).** The `ReceiptValidator` seam from BILL-1 needs real platform impls before any real-money sale; a forged receipt must be rejected.
  **Acceptance:** Apple impl verifies the StoreKit 2 signed-transaction JWS via the official app-store-server-library (Java) — checks bundle id, product id, and `appAccountToken == userId`. Google impl calls `purchases.products.get`, checks `purchaseState == purchased` + `obfuscatedExternalAccountId == userId`, then acknowledges/consumes. Both read credentials from server config and stay dormant (validation refused) when unset. Live exercise against the stores is developer-gated.
  **Hints:** both official libs run on the JVM Ktor server; mirror `SentryConfig.fromEnv` for the dormant-until-configured pattern; pin the purchase to the user via the echoed-back account token, **not** orderId — and fix the now-wrong `BillingClient` doc comment that says orderId.

- `[P1]` **BILL-3 — Android `PlayBillingClient`.** `libraries/billing/impl/src/androidMain` is empty, so release builds fall back to `NoOpBillingClient` (no IAP).
  **Acceptance:** implements [BillingClient](libraries/billing/src/commonMain/kotlin/com/cards/libraries/billing/BillingClient.kt) against the Play Billing Library (v7+; check for v8 at build time) — `connect`/`queryProducts`/`purchase`, forwarding `userId` via `setObfuscatedAccountId`. Chip packs are **consumables**, so the success path must `consumeAsync` (not just acknowledge) or a re-purchase is impossible — add a `consume()` to the interface (or rework `acknowledge`). Bound with `@ContributesBinding(replaces = [DevBillingClient::class])`, selected by the BILL-5 flag.
  **Hints:** keep behaviour identical to `FakeBillingClient`; local verification uses Play static-response test SKUs / license testers (developer-gated).

- `[P1]` **BILL-4 — iOS `StoreKitBillingClient`.** `libraries/billing/impl/src/iosMain` is empty; iOS release builds have no IAP.
  **Acceptance:** implements `BillingClient` with StoreKit 2 (`Product.purchase()`, `Transaction`, `transaction.finish()` for consumables), forwarding `userId` as `appAccountToken` (a UUID — Supabase user ids already qualify). Verifiable locally via an Xcode `.storekit` test config — **no App Store Connect needed for dev iteration**.
  **Hints:** StoreKit 2 async API; pairs with the BILL-2 Apple validator (same `appAccountToken` pin).

