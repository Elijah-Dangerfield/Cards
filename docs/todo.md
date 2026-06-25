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

### Multiplayer & rooms

- `[P0]` **MP-13 — MP wallet settlement doesn't conserve chips across a game.** Two humans played heads-up and the sum of their wallets *grew* (10k+10k → 22000, +2000 minted). The server is authoritative for sit-down debit + cash-out credit via the wallet ledger, but nothing enforces conservation, and the existing `fullHandThenBothLeave_conservesEveryChip` test passes — so the leak is in a multi-hand / rebuy path it doesn't cover.
  **Acceptance:** A full MP game including rebuys settles wallets so `sum(after) == sum(before)` exactly (zero rake in V1); an integration test reproduces the +2000 and then asserts exact conservation.
  **Diagnosis (pinned):** the mint vector is `DefaultTableSessionService.cashOut` with `finalStack == null` → it refunds the **full escrow** `buyIn * (1 + rebuyCount)`, over-crediting a player who *lost* chips (those chips are already in the opponent's stack, so they get double-counted). The clean leave path conserves — it sources the real live stack via `stackFor` before cashing out. The null path fires where the live stack is unknown: the teardown coordinator with a null `state`, the recovery sweep with no persisted snapshot, or a disconnect-timing window. Fix: make cash-out always settle the real last-known stack (persist it per session / always source from the snapshot), and reserve the full-escrow refund strictly for "no hand was ever dealt." Add a multi-hand + rebuy conservation test (needs a deterministic bust → disconnect/teardown repro, which the passive harness can't produce today — build that first).
  **Hints:** `DefaultTableSessionService.kt:195` (the `finalStack ?: …` refund), `RoomTeardownCoordinator.kt:49`, `DefaultTableSessionRecoverySweep.kt`, `SeatStack.stackFor`. Case `docs/agent/feedback-cases/7b9fada4e2364ed6971fffef505ec57b.md`; Sentry CARDS-3V.

- `[P0]` **MP-14 — Wire + client for the heads-up match-over (server core landed).** Detection + grace timer are built: `MatchOverGraceDriver` (one per session in the registry, mirrors `TurnTimerDriver`) spots the terminal heads-up bust (hand `Complete`, exactly one seat with chips, a busted human), emits `GraceStarted(deadline, bustedSeat)`, waits ~60s — cancelled by `collectLatest` the moment a rebuy/leave changes state — then emits `Resolved(winner)`, all via `GameSession.matchOverEvents`. **Remaining:** (1) **wire** — add MatchOverPending / MatchOverCleared / MatchOverResolved to the server `RoomSocketEvent.kt` *and* client `RoomSocketEventDto.kt` (matching `@SerialName`, + a `summary()` case); (2) **routes** — `RoomSocketRoutes` forwards `session.matchOverEvents` to subscribers the way it forwards `emojiBlasts`, and on `Resolved` flips `RoomStatus.Finished` + cashes the winner out; (3) **client** — render the live countdown both roles (busted: "rebuy in Ns or lose your seat" + a rebuy CTA; winner: "auto-continues in Ns"), route to a match-over result on `Resolved`, and let the winner end early by leaving.
  **Acceptance:** Busted player sees a countdown ("Buy back in within Xs or you'll lose your seat"). Standing player sees "Waiting for your opponent to rebuy or leave" with the live countdown / auto-continue cue. On expiry the busted seat is forfeited → standing player sees a match-over result and is routed off the dead table; winner can also end early. No indefinite "waiting" loop.
  **Hints:** Forward pattern = how `emojiBlasts` reaches the client (`GameSession` SharedFlow → `RoomSocketRoutes` per-subscriber forward → wire event). `RoomStatus.Finished` / `RoomStatusDto.Finished` already exist. Case `docs/agent/feedback-cases/e98cfac9d86545ad89083f7341e6f22a.md`; Sentry CARDS-3S.

- `[P1]` **MP-16 — Fix the post-leave $0 buy-in rebound.** A $0 room is already structurally impossible: the create-form slider seeds `DEFAULT_BUY_IN` (`0c4f28a9`) and the server rejects out-of-range buy-ins (`RoomRoutes.kt` create floor). So the remaining user-visible $0 — sole human routed back to the in-room lobby after the other player leaves — is a **stale/placeholder client `Room` snapshot**, not a real room state. Since `buyIn == 0` now provably means "not a real snapshot," make the client refuse to render/stage it (retain the last known-good room rather than overwrite with a $0 one), then remove the `LobbyScreen` band-aid (`if (room.buyIn > 0)` at `LobbyScreen.kt:383`).
  **Acceptance:** After the opponent leaves, the sole-human lobby keeps showing the real stakes; no $0 ever renders; the band-aid is gone.
  **Hints:** Client room state flows through `RoomRepositoryImpl.upsertActive` → lobby state; the rebound snapshot arrives after `MemberLeft`. See backlog "$0 buy-in … after sole-human-left rebound". Case `docs/agent/feedback-cases/a3b1fc7d414444b295669d047d173ff8.md`; Sentry CARDS-3X/CARDS-3N.
