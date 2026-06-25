# TODO

**Last reviewed:** 2026-06-25 (feedback triage: CARDS-3N…47) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## A. UX gaps

### Progression & stats

- `[P1]` **PROG-1 — Convert the achievement engine to predicates over `PlayerStats`.** The achievement engine still accumulates its own per-id progress in `AchievementRepositoryImpl.recordHand` (`counters` / `customCounters`), so achievement bars reset on account-switch / reinstall. Make the predicates read cumulative counters / no-bust streak / per-bot wins off the server-authoritative `PlayerStats` snapshot, recording a `claimed_at_value` per achievement so each unlocks once and doesn't re-fire.

  **Acceptance:** Sign in on a second device → correct achievement progress after one sync; stats screen and achievement bars agree; a new achievement points at an existing stat with no data migration.

  **Hints:** `PlayerStats` is already an offline-first `observeStats(): Flow<PlayerStats?>` (`PlayerStatsRepositoryImpl`). Touches the live unlock path — land as its own commit with the achievement-unlock tests green.

### Auth & onboarding

- `[P2]` **AUTH-1 — Tune the device-verify banner placement.** Copy is refined; what's left is where the banner sits relative to the verify CTA on `VerifyEmailScreen` — eyeball the spacing/position against the screen and adjust.

  **Hints:** Verify surface is `features/onboarding/impl/AuthScreens.kt` (`VerifyEmailScreen`, banner sits between body + the Check-verification button). Needs Studio to eyeball.

### Gameplay & table UX

- `[P2]` **GAME-3 — Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint.

  **Acceptance:** The glyph reads optically centered in the circle at every `Size`.

  **Hints:** The `Box`/`Text` in `EmojiButton.kt`; likely needs a glyph-vs-line-box offset, not just `Alignment.Center`. **Worker note:** needs Studio to eyeball against the size-scale `@Preview`.

### Multiplayer & rooms

- `[P0]` **MP-13 — MP wallet settlement doesn't conserve chips across a game.** Two humans each started a heads-up game and the sum of their wallets *grew* (10k+10k → 12450+9550 = 22000, +2000 minted). The table-chip side is conserved; the wallet settlement is client-derived with no server-authoritative or test-enforced conservation invariant.
  **Acceptance:** A full MP game settles wallets so `sum(wallets_after) == sum(wallets_before)`; an integration test plays a complete MP game and asserts conservation.
  **Hints:** MP chip escrow/settlement shipped in #67; make the server authoritative for MP buy-in debit + pot payout (one ledger entry per seat per game) rather than each client computing its own delta. Likely interacts with the $0-buy-in bug (MP-16). Case `docs/agent/feedback-cases/7b9fada4e2364ed6971fffef505ec57b.md`; Sentry CARDS-3V.

- `[P0]` **MP-14 — Heads-up bust deadlocks the table.** When the loser busts to 0 in a heads-up game, the server (correctly) rejects the next hand with `not enough players with chips for next hand`, but the winner's "next hand" button silently does nothing and the busted player is stuck on "you're out of chips" with no rebuy / match-over / leave resolution.
  **Acceptance:** On a heads-up bust, the winner sees a match-over result (takes the table) or the busted player gets a rebuy — no dead "next hand" button, no stuck "out of chips" screen.
  **Hints:** Triggered by the server's `not enough players with chips for next hand` intent_ack rejection at hand end (RequestNextHand). Terminal-state cousin of the prior hand-end-stall family (CARDS-25/16) but a *legitimate* bust — needs a product call on rebuy vs match-over. Case `docs/agent/feedback-cases/e98cfac9d86545ad89083f7341e6f22a.md`; Sentry CARDS-3S.

- `[P1]` **MP-15 — Public matchmaking opens a fresh room instead of joining an existing open one.** Player A opens a public room; Player B's "Find a Room" spins up a brand-new empty room and leaves B stuck on the "searching" screen. B only got in by leaving and joining A's room by code.
  **Acceptance:** With one eligible open room, `find` lands the searcher in it (members=2), never a new room; a freshly-opened room appears in `candidates` without delay. Covered by a test: A opens → B's find joins A's room.
  **Hints:** Server log shows `Matchmaking opened public room <new> …` (`InMemoryRoomService.findOrJoinPublic`, ~line 276) firing while an open room existed — join-existing must beat open-new; check the open-room eligibility/visibility filter and any in-memory registry race. Public matchmaking shipped in #67. Case `docs/agent/feedback-cases/cffeaf3aecbd49cd9aacb0ca1daa0155.md`; Sentry CARDS-3Z (+ CARDS-40, CARDS-45).

- `[P1]` **MP-16 — Create-room buy-in defaults to 0 until the slider is touched.** Two testers independently created rooms with a $0 buy-in because the create-room buy-in slider starts at 0 — tapping Create without dragging it yields a meaningless $0 game (and likely feeds the wallet-conservation bug MP-13).
  **Acceptance:** The create-room buy-in slider is seeded to a sensible non-zero default (lowest tier); Create is blocked at buy-in 0.
  **Hints:** Create-room form / its slider initial value. Distinct from the backlog post-leave "$0 buy-in after sole-human-left rebound" symptom. Case `docs/agent/feedback-cases/a3b1fc7d414444b295669d047d173ff8.md`; Sentry CARDS-3X (+ CARDS-3N).

---

## B. Engineering

### Lint / static analysis

- `[P1]` **ENG-2 — Stand up detekt as the custom-rule framework, gated in CI + pre-push.** Give the build a growable way to mechanically enforce AGENTS.md conventions. Land the framework + the first rule; later rules (`Catching` over `runCatching`, `DispatcherProvider` over direct `Dispatchers.*`, raw `Color(0xFF…)` for semantic surfaces) are cheap follow-on items.

  - **Framework:** add detekt to `gradle/libs.versions.toml` + a `build-logic/` convention plugin, wire it into `check` and a new `.githooks/pre-push`, behind a baseline so the gate is green day one.
  - **Rule #1 — `verifyStrings`:** fail on inline user-facing string literals (`Text("…")`, `placeholder = "…"`, VM copy) outside `:libraries:resources`, allowlisting glyph-only / preview / server-supplied strings.

  **Acceptance:** `Text("Hello")` in a feature `:impl` fails both `./gradlew check` and pre-push; `stringResource(...)` passes; a suppress annotation clears a line; adding a second rule is a new rule class + config entry, no framework rework.

  **Blocker (needs a human call first):** repo is on Kotlin 2.3.21; detekt 1.23.x bundles the 2.0.0 compiler and chokes on 2.3 metadata (detekt#8865). Only `dev.detekt` 2.0.0-alpha supports Kotlin 2.3 — pin the alpha deliberately or hold until detekt 2 stabilises.

  **Out of scope:** migrating existing string violations — separate cleanup once the gate exists.

### Billing

- `[P0]` **BILL-1 — Server-side IAP receipt validation + server-authoritative purchase ledger.** Today `ShopViewModel.ConfirmPendingPurchase` drives `billingClient.purchase(...)` and credits chips locally on success — the server never validates the receipt, so a forged receipt mints chips. Before any real-money sale: `POST /v1/billing/redeem` validates against the Apple App Store Server API / Google Play Developer API, then grants chips through the server wallet ledger, idempotent per store transaction id. **Gated on:** store IAP products + store API credentials existing (developer-todo).

  **Hints:** Grant precedent is `ChipsRepository.addChips(idempotencyKey=…)` / the wallet ledger. Verify-before-credit — never trust the client for paid chips.
