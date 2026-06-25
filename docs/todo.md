# TODO

**Last reviewed:** 2026-06-25 (decisions pass: every item made worker-pickable; BILL-1 → developer-todo) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- `[P1]` **PROG-1 — Make the achievement engine server-authoritative over player stats.** `AchievementRepositoryImpl` accumulates its own per-id progress via local `AchievementDao` counters, so bars reset on account-switch / reinstall. Expand the server stats so it computes/stores every counter the predicates need (reshape `PlayerStatsDto` or add a dedicated achievements-stats endpoint — whichever is cleanest), convert the client predicates to read that snapshot, and record a `claimed_at_value` per achievement so each unlocks once. ~20 counters need server backing today (pot high-water marks, comeback/recovery, good-fold, all-in, doubles/triples, busts-dealt, the 9 hand-strength shows, level) — the rest (`handsPlayed`, no-bust streak, `perBotWins`) already exist on `PlayerStats`.

  **Acceptance:** Sign in on a second device → correct progress after one sync; achievement bars agree with the stats screen.

  **Hints:** Land server schema + endpoint and the client conversion together, achievement-unlock tests green. Server: `apps/server/.../PlayerStatsRepository.kt`, `PlayerStatsDto.kt`, a migration. Client: `AchievementRepositoryImpl.kt`, `AchievementRegistry.kt`, `PlayerStats.kt`.

### Gameplay & table UX

- `[P2]` **GAME-3 — Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint.

  **Acceptance:** The glyph reads optically centered in the circle at every `Size`.

  **Hints:** The `Box`/`Text` in `EmojiButton.kt`; needs a glyph-vs-line-box `offset`, not just `Alignment.Center`. Validate against the `EmojiButtonPreview_Sizes` size-scale `@Preview` screenshot — no device required.

### Multiplayer & rooms

- `[P0]` **MP-13 — MP wallet settlement doesn't conserve chips across a game.** Two humans played heads-up and the sum of their wallets *grew* (10k+10k → 22000, +2000 minted). The server is authoritative for sit-down debit + cash-out credit via the wallet ledger, but nothing enforces conservation, and the existing `fullHandThenBothLeave_conservesEveryChip` test passes — so the leak is in a multi-hand / rebuy path it doesn't cover.
  **Acceptance:** A full MP game including rebuys settles wallets so `sum(after) == sum(before)` exactly (zero rake in V1); an integration test reproduces the +2000 and then asserts exact conservation.
  **Hints:** `DefaultTableSessionService.kt` (sit/cash-out/rebuy ledger), `GameSession.kt` (settlement), `apps/integration/.../ChipEconomyPlayTest.kt`. A $0-buy-in seat is a contributor — the MP-16 buy-in floor shrinks this surface. Case `docs/agent/feedback-cases/7b9fada4e2364ed6971fffef505ec57b.md`; Sentry CARDS-3V.

- `[P0]` **MP-14 — Heads-up bust needs a terminal match-over resolution.** If the busted player can't or won't rebuy, both players idle indefinitely on the "waiting for opponent to rebuy or leave" notice — there's no terminal path that resolves the table to a match-over. Resolve via a rebuy-grace timeout (~60s).
  **Acceptance:** Busted player sees a countdown ("Buy back in within Xs or you'll lose your seat"). Standing player sees "Waiting for your opponent to rebuy or leave" with the same countdown / auto-continue cue. On expiry, auto-forfeit the busted seat → standing player sees a match-over result and is routed off the dead table; winner can also end early. No indefinite "waiting" loop.
  **Hints:** Set `RoomStatus.Finished` (defined in `Room.kt`, never set today). Build on server `forfeitSeat`/`removePlayer` in `GameSession.kt` + the grace timer; broadcast a match-over signal via `RoomSocketRoutes.kt`; client surfaces it in `RemotePokerSession.kt` (`nextHandUnavailable` already wired) + the waiting/match-over UI. Case `docs/agent/feedback-cases/e98cfac9d86545ad89083f7341e6f22a.md`; Sentry CARDS-3S.

- `[P1]` **MP-16 — Fix the post-leave $0 buy-in rebound.** A $0 room is already structurally impossible: the create-form slider seeds `DEFAULT_BUY_IN` (`0c4f28a9`) and the server rejects out-of-range buy-ins (`RoomRoutes.kt` create floor). So the remaining user-visible $0 — sole human routed back to the in-room lobby after the other player leaves — is a **stale/placeholder client `Room` snapshot**, not a real room state. Since `buyIn == 0` now provably means "not a real snapshot," make the client refuse to render/stage it (retain the last known-good room rather than overwrite with a $0 one), then remove the `LobbyScreen` band-aid (`if (room.buyIn > 0)` at `LobbyScreen.kt:383`).
  **Acceptance:** After the opponent leaves, the sole-human lobby keeps showing the real stakes; no $0 ever renders; the band-aid is gone.
  **Hints:** Client room state flows through `RoomRepositoryImpl.upsertActive` → lobby state; the rebound snapshot arrives after `MemberLeft`. See backlog "$0 buy-in … after sole-human-left rebound". Case `docs/agent/feedback-cases/a3b1fc7d414444b295669d047d173ff8.md`; Sentry CARDS-3X/CARDS-3N.

---

## B. Engineering

### Lint / static analysis

- `[P1]` **ENG-2 — Stand up detekt as the custom-rule framework, gated in CI + pre-push.** Give the build a growable way to mechanically enforce AGENTS.md conventions. Land the framework (detekt in `libs.versions.toml` + a `build-logic/` convention plugin, wired into `check` and a new `.githooks/pre-push`, behind a baseline) plus rule #1 — `verifyStrings`: fail on inline user-facing string literals outside `:libraries:resources`, allowlisting glyph-only / preview / server-supplied strings.

  **Acceptance:** `Text("Hello")` in a feature `:impl` fails both `./gradlew check` and pre-push; `stringResource(...)` passes; a suppress annotation clears a line; a second rule is just a new rule class + config entry.

  **Hints:** Pin `dev.detekt` 2.0.0-alpha (the only line that supports Kotlin 2.3.21; it's a dev/CI-only build dependency, never shipped to users — contain blast radius behind a baseline). `gradle/libs.versions.toml`, a new `build-logic/` convention plugin, `.githooks/pre-push`.

  **Out of scope:** migrating existing string violations.
