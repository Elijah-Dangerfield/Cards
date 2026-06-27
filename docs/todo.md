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

## D. Multiplayer hardening

- `[P1]` **MP-23 — Wallet doesn't reconcile when the host leaves via the back button (only on next foreground).** A real-chip MP balance stays at the escrowed value after leaving via the top back-arrow / system back; the settled balance only appears on the next app foreground — the exact symptom `reconcileWalletAfterGame` was built to kill (CARDS-3C/4B). The in-screen Leave action reconciles; the back-button leave path bypasses it (VM popped before the reconcile runs).
  **Acceptance:** every leave path — in-screen Leave, top back-arrow, and system back — funnels through `leaveAndReconcileWallet` before the screen pops; a failing-then-passing test pops the play screen via back and asserts a post-leave wallet sync fired and the cash-out toast showed.
  **Hints:** `leaveAndReconcileWallet` / `reconcileWalletAfterGame` in [PlayPokerViewModel.kt](features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayPokerViewModel.kt):601-641; related to ROOM-2 (two leave paths diverge) but this is the wallet reconcile, not nav. Case `docs/agent/feedback-cases/113c61ec949f463692165413177659e9.md`, Sentry [CARDS-5B](https://elijah-dangerfield.sentry.io/issues/CARDS-5B).

- `[P1]` **MP-24 — Lobby buy-in still renders 0 for a joiner (MP-16 reopen, post-#74).** MP-16's $0-buy-in fix (PR #74: `Room.preferRealOver` / `isPlaceholder`) guards the host's create + socket-rebound staging, but a joiner who enters via PrivateJoin still sees the lobby buy-in render as 0 on a post-#74 build. The room's real buy-in was applied server-side (stacks debited); only the joiner's lobby snapshot shows 0.
  **Acceptance:** joining a real-buy-in room never shows a $0 lobby buy-in; a failing-then-passing test joins a room with a non-zero buy-in and asserts the lobby value is the real buy-in, not a placeholder.
  **Hints:** apply the `preferRealOver` / `isPlaceholder (buyIn <= 0)` invariant on the joiner's lobby snapshot read (PrivateJoin → lobby), not just the create seed; same files MP-16 touched (`RoomRepositoryImpl.upsertActiveRoom`, `ReconnectingRoomSocket` snapshot emission). Case `docs/agent/feedback-cases/ee5bfb6407cb421592a7e501eab916b5.md`, Sentry [CARDS-55](https://elijah-dangerfield.sentry.io/issues/CARDS-55).

---

## E. Rooms UI

- `[P2]` **ROOM-4 — Show the win/loss this leave will settle in the leave-confirmation dialog.** Players leaving an MP table can't see what leaving does to their chips: they win a pot, leave at the start of the next hand, and are surprised by the net (a posted blind already forfeited). Two players asked for the same thing — "make people super in control of their money." The chip math is correct; the gap is visibility at the leave moment.
  **Acceptance:** the MP leave-confirm dialog shows the net chips this leave will settle (and calls out a posted blind being forfeited when applicable). Ship a slice; a directional call on the secondary ask — letting a player leave *before* the next hand's blinds are posted — can be a follow-up note, not a requirement.
  **Hints:** leave-confirm surface today is [ui/LeaveBotsConfirmDialog.kt](features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/ui/LeaveBotsConfirmDialog.kt); the settle delta is the same value `reconcileWalletAfterGame` computes. Cases `docs/agent/feedback-cases/6eaea8834468472d91186958d94d2fc8.md` + `fd024476465049c09b24c1193c338d7a.md`, Sentry [CARDS-59](https://elijah-dangerfield.sentry.io/issues/CARDS-59) / [CARDS-57](https://elijah-dangerfield.sentry.io/issues/CARDS-57).

---

## F. Shop & cosmetics

- `[P2]` **SHOP-3 — Host-chosen felt + card backs, shown to every player at the table.** Owner directive: let the game creator pick the felt and card backs from their inventory when creating a room, and have *every* player at the table see the host's chosen felt and card backs (incentivizes buying cosmetics). The host's selection already exists per-player; this makes it table-wide.
  **Acceptance:** create-room flow lets the host pick an owned felt + card back; the room snapshot carries them; all clients render the host's felt and card backs in-game. Ship a slice + a directional call on edge cases (host has none equipped → table default; whether a player's own equipped back still applies to their own cards) and let the reviewer course-correct.
  **Hints:** plumbing mirrors the "Player Card — Phase 2: opponent cosmetics over the wire" backlog item — put the host's equipped felt/back on the room/seat snapshot and read it at the play surface instead of the local `EquippedFelt` derived from the player's own inventory (`feltForProductId` in `PlayPokerViewModel`). Owner directive, Sentry [CARDS-4Q](https://elijah-dangerfield.sentry.io/issues/CARDS-4Q).

---

## G. Billing & IAP

Native IAP (Play Billing + StoreKit 2 + own server validation — no RevenueCat). The `BillingClient` abstraction, fake/dev clients, server wallet ledger, and idempotent grant already exist; these items fill the two real gaps (platform clients + server validation) and make the credit server-authoritative. Live store testing for several items is developer-gated on credentials/listings — those gates live in [developer-todo.md](./developer-todo.md); the code is buildable and unit/locally-testable now.

- `[P0]` **BILL-1 — Server `/v1/billing/redeem` endpoint with idempotent grant + `ReceiptValidator` seam.** Today the shop credits chips locally on store confirmation ([DefaultPurchaseChipPackUseCase.kt](libraries/billing/impl/src/commonMain/kotlin/com/cards/libraries/billing/impl/DefaultPurchaseChipPackUseCase.kt)); the server never sees the receipt, so a forged one mints chips.
  **Acceptance:** an authenticated `POST /v1/billing/redeem` takes `{ platform, productId, purchaseToken|signedTransaction }`, runs it through a `ReceiptValidator` interface (fake impl for tests; real impls are BILL-2), and on success grants chips through the wallet ledger keyed on the store transaction id. A `billing_transactions` table with `UNIQUE(store, order_id)` makes redemption idempotent. Returns the authoritative balance.
  **Hints:** JWT auth plugin → `call.userId()`; grant precedent `WalletRepository.apply(idempotencyKey=…)`; new Flyway migration alongside `apps/server/src/main/resources/db/migration/V5__products.sql`.

- `[P0]` **BILL-2 — Real receipt validators (Apple App Store Server API + Google Play Developer API).** The `ReceiptValidator` seam from BILL-1 needs real platform impls before any real-money sale; a forged receipt must be rejected.
  **Acceptance:** Apple impl verifies the StoreKit 2 signed-transaction JWS via the official app-store-server-library (Java) — checks bundle id, product id, and `appAccountToken == userId`. Google impl calls `purchases.products.get`, checks `purchaseState == purchased` + `obfuscatedExternalAccountId == userId`, then acknowledges/consumes. Both read credentials from server config and stay dormant (validation refused) when unset. Live exercise against the stores is developer-gated.
  **Hints:** both official libs run on the JVM Ktor server; mirror `SentryConfig.fromEnv` for the dormant-until-configured pattern; pin the purchase to the user via the echoed-back account token, **not** orderId — and fix the now-wrong `BillingClient` doc comment that says orderId.

- `[P1]` **BILL-3 — Android `PlayBillingClient`.** `libraries/billing/impl/src/androidMain` is empty, so release builds fall back to `NoOpBillingClient` (no IAP).
  **Acceptance:** implements [BillingClient](libraries/billing/src/commonMain/kotlin/com/cards/libraries/billing/BillingClient.kt) against the Play Billing Library (v7+; check for v8 at build time) — `connect`/`queryProducts`/`purchase`, forwarding `userId` via `setObfuscatedAccountId`. Chip packs are **consumables**, so the success path must `consumeAsync` (not just acknowledge) or a re-purchase is impossible — add a `consume()` to the interface (or rework `acknowledge`). Bound with `@ContributesBinding(replaces = [DevBillingClient::class])`, selected by the BILL-5 flag.
  **Hints:** keep behaviour identical to `FakeBillingClient`; local verification uses Play static-response test SKUs / license testers (developer-gated).

- `[P1]` **BILL-4 — iOS `StoreKitBillingClient`.** `libraries/billing/impl/src/iosMain` is empty; iOS release builds have no IAP.
  **Acceptance:** implements `BillingClient` with StoreKit 2 (`Product.purchase()`, `Transaction`, `transaction.finish()` for consumables), forwarding `userId` as `appAccountToken` (a UUID — Supabase user ids already qualify). Verifiable locally via an Xcode `.storekit` test config — **no App Store Connect needed for dev iteration**.
  **Hints:** StoreKit 2 async API; pairs with the BILL-2 Apple validator (same `appAccountToken` pin).

- `[P1]` **BILL-5 — Server-authoritative chip credit + `billing.realPurchasesEnabled` gate.** [DefaultPurchaseChipPackUseCase.kt](libraries/billing/impl/src/commonMain/kotlin/com/cards/libraries/billing/impl/DefaultPurchaseChipPackUseCase.kt) credits chips locally before any server validation (a double-credit window it admits to in its own comment), and there's no flag to ship real billing dark.
  **Acceptance:** the purchase flow becomes validate → grant → consume: store confirms → `POST /v1/billing/redeem` → reflect the server-returned balance → then consume/acknowledge. A config flag both selects the real-vs-Dev billing client and lets the code ship dark; per-environment via the config Postgres.
  **Hints:** `creditChipsFor` is the seam to invert; config-flag precedent in `:libraries:config`; depends on BILL-1 (endpoint) — BILL-3/4 can land independently.

---

## H. Engineering & structural

- `[P1]` **ENG-6 — Verify the force-update gate reliably covers an already-in-game client.** The cross-version rule (decisions.md 2026-06-27, CARDS-4S) relies on raising `upgrade.minSupportedVersionCode` to lock out clients too old to parse a breaking game-object change. `AppGuardState.from` already evaluates the *streamed* config map and `AppGuardLayer` renders an app-wide `UpgradeRequired` overlay, so it should take effect live — but this hasn't been verified for a client mid-hand.
  **Acceptance:** a test (or documented manual check) confirms that bumping `minSupportedVersionCode` in streamed config raises the blocking overlay over the play screen for an in-session client within the config refresh window — not only on next cold boot. If the refresh cadence or overlay z-order leaves a gap, close it.
  **Hints:** [AppGuard.kt](features/upgrade/src/commonMain/kotlin/com/cards/features/upgrade/AppGuard.kt), [AppGuardLayer.kt](features/upgrade/impl/src/commonMain/kotlin/com/cards/features/upgrade/impl/AppGuardLayer.kt), config key `upgrade.minSupportedVersionCode`; check the `AppConfigMap` stream refresh cadence (push vs poll) and that the overlay sits above the nav graph including the play surface.
