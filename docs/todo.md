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

- `[P1]` **PROG-1 — Convert the achievement engine to predicates over `PlayerStats`.** `AchievementRepositoryImpl` still accumulates its own per-id progress via `AchievementDao` counters, so achievement bars reset on account-switch / reinstall. Make the predicates read cumulative counters / no-bust streak / per-bot wins off the server-authoritative `PlayerStats` snapshot, recording a `claimed_at_value` per achievement so each unlocks once and doesn't re-fire.

  **Acceptance:** Sign in on a second device → correct achievement progress after one sync; achievement bars agree with the stats screen; a new achievement points at an existing stat with no data migration.

  **Hints:** `PlayerStatsRepository` (server-backed, `observeStats(): Flow<PlayerStats?>`) is already wired into the stats screen. Touches the live unlock path — land as its own commit with achievement-unlock tests green.

### Auth & onboarding

- `[P2]` **AUTH-1 — Tune the device-verify banner placement.** Copy is refined; what's left is where the banner sits relative to the verify CTA on `VerifyEmailScreen` — eyeball the spacing/position against the screen and adjust.

  **Hints:** Verify surface is `features/onboarding/impl/AuthScreens.kt` (`VerifyEmailScreen`, banner sits between body + the Check-verification button). Needs Studio to eyeball.

### Gameplay & table UX

- `[P2]` **GAME-3 — Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint.

  **Acceptance:** The glyph reads optically centered in the circle at every `Size`.

  **Hints:** The `Box`/`Text` in `EmojiButton.kt`; likely needs a glyph-vs-line-box offset, not just `Alignment.Center`. **Worker note:** needs Studio to eyeball against the size-scale `@Preview`.

### Multiplayer & rooms

- `[P0]` **MP-13 — MP wallet settlement doesn't conserve chips across a game.** Two humans played heads-up and the sum of their wallets *grew* (10k+10k → 22000, +2000 minted). Table chips are conserved; the wallet settlement is client-derived with no server-authoritative or test-enforced conservation invariant.
  **Acceptance:** A full MP game settles wallets so `sum(wallets_after) == sum(wallets_before)`; an integration test plays a complete MP game and asserts conservation.
  **Hints:** Make the server authoritative for MP buy-in debit + pot payout (one ledger entry per seat per game) rather than each client computing its own delta. Sentry CARDS-3V.

- `[P0]` **MP-14 — Heads-up bust needs a terminal match-over resolution.** If the busted player can't or won't rebuy, both players idle indefinitely on the "waiting for opponent to rebuy or leave" notice — there's no terminal path that resolves the table to a match-over (winner takes the table).
  **Acceptance:** On a heads-up bust where the loser doesn't rebuy, the winner sees a match-over result and is routed off the dead table — no indefinite "waiting" loop.
  **Hints:** Needs a product call on the resolution trigger (busted-player leave, a rebuy-grace timeout, or an explicit winner "end match") plus a server-driven match-over signal — no match-over concept exists today. Client already surfaces the rejection via `PokerSession.nextHandUnavailable`. Sentry CARDS-3S.

- `[P1]` **MP-15 — Public matchmaking opens a fresh room instead of joining an existing open one.** Player A opens a public room; Player B's "Find a Room" spins up a brand-new empty room and strands B on the "searching" screen instead of seating them with A.
  **Acceptance:** With one eligible open room, `find` lands the searcher in it (members=2), never a new room. Covered by a test: A opens → B's find joins A's room.
  **Hints:** `InMemoryRoomService.findOrJoinPublic` logs `Matchmaking opened public room …` while an open room exists — join-existing must beat open-new; check the open-room eligibility/visibility filter and any in-memory registry race. Sentry CARDS-3Z.

- `[P1]` **MP-16 — Pin where the lobby's $0 buy-in snapshot leaks from.** The lobby now suppresses the stakes row while `room.buyIn == 0`, so testers no longer see a flashed "$0" — but which snapshot path stages a `buyIn == 0` room is still unconfirmed. Every wire path carries the real buy-in via `Room.toDto()`, so the 0 likely comes from a partial/transient snapshot or a persisted-room restore (`PostgresRoomStore`) reading an unwritten column. Needs runtime traces from a repro to pin and fix at the source.
  **Hints:** `RoomDto.buyIn` / `Room.buyIn` both default to 0. Sentry CARDS-3X.

---

## B. Engineering

### Lint / static analysis

- `[P1]` **ENG-2 — Stand up detekt as the custom-rule framework, gated in CI + pre-push.** Give the build a growable way to mechanically enforce AGENTS.md conventions. Land the framework (detekt in `libs.versions.toml` + a `build-logic/` convention plugin, wired into `check` and a new `.githooks/pre-push`, behind a baseline) plus rule #1 — `verifyStrings`: fail on inline user-facing string literals outside `:libraries:resources`, allowlisting glyph-only / preview / server-supplied strings.

  **Acceptance:** `Text("Hello")` in a feature `:impl` fails both `./gradlew check` and pre-push; `stringResource(...)` passes; a suppress annotation clears a line; a second rule is just a new rule class + config entry.

  **Blocker (needs a human call first):** repo is on Kotlin 2.3.21; detekt 1.23.x chokes on 2.3 metadata (detekt#8865). Only `dev.detekt` 2.0.0-alpha supports Kotlin 2.3 — pin the alpha deliberately or hold until detekt 2 stabilises.

  **Out of scope:** migrating existing string violations.

### Billing

- `[P0]` **BILL-1 — Server-side IAP receipt validation + server-authoritative purchase ledger.** Today `ShopAction.ConfirmPurchase` drives the local purchase use case and credits chips on success — the server never validates the receipt, so a forged receipt mints chips. Before any real-money sale: `POST /v1/billing/redeem` validates against the Apple App Store Server API / Google Play Developer API, then grants chips through the server wallet ledger, idempotent per store transaction id. **Gated on:** store IAP products + store API credentials existing (developer-todo).

  **Hints:** Grant precedent is `ChipsRepository.addChips(idempotencyKey=…)` / the wallet ledger. Verify-before-credit — never trust the client for paid chips.
