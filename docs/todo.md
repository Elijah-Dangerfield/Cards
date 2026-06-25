# TODO

**Last reviewed:** 2026-06-25 (decisions pass: every item made worker-pickable; BILL-1 → developer-todo) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

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
  **Audit siblings (same fix):** (a) a player who **busts out then leaves/teardowns** mints too, and worse — `requestNextHand` drops the 0-stack seat, so the snapshot has *no seat* for them and even "source from the snapshot" still resolves null → full-escrow refund; (b) two **concurrent cashouts** (leave vs teardown vs boot sweep) with different `finalStack` args race — the ledger dedupes the credit by key but the *amount* is whichever commits first, which can be the full-escrow value. Both converge on the fix: persist a per-session live stack on every state change, cash out exactly that, full-escrow refund only when `handNumber == 0`.
  **Hints:** `DefaultTableSessionService.kt:195` (the `finalStack ?: …` refund), `RoomTeardownCoordinator.kt:49`, `DefaultTableSessionRecoverySweep.kt`, `SeatStack.stackFor`, `GameSession.requestNextHand` (drops busted seats). Needs MP-18's deterministic-bust harness to test. Case `docs/agent/feedback-cases/7b9fada4e2364ed6971fffef505ec57b.md`; Sentry CARDS-3V.

- `[P1]` **MP-17 — Harden + test the MP bust/leave terminal edge cases (audit).** Beyond the heads-up bust (MP-14): a multi-way hand busting to **zero survivors** (side-pot chop) has no terminal path — `MatchOverGraceDriver.terminalBust` only fires for exactly one survivor — so the table wedges; a **winner leaving mid-hand while all-in** can win a pot they walked away from after being cashed out at a pre-showdown stack (chip skew); **3→2→1 collapse**, **simultaneous mid-hand leaves**, and the **reaper grace expiring mid-hand** are all untested.
  **Acceptance:** each scenario resolves cleanly (terminal or continue) with chips conserved; integration tests cover them.
  **Hints:** needs MP-18's deterministic-bust harness. Server: `MatchOverGraceDriver`, `GameSession.forfeitSeat`/`removePlayer`, the `MemberLeft` handling in `RoomSocketRoutes`.

- `[P1]` **MP-18 — MP integration-harness investments to unblock bust/money tests (audit).** The passive harness can't deterministically bust a player (random decks), can't rebuy (`GameplaySession` has no `rebuy()`), and `FakeRoomServer` hard-rejects rebuy frames — so MP-13/MP-17 and the money-conservation tests can't be written. Add a **seeded/stacked-deck injection** point, a `GameplaySession.rebuy()` helper, a `FakeRoomServer` rebuy ack, and an **end-to-end match-over test** (grace is now injectable via `matchOverGraceMillis` on the registry).
  **Hints:** harness in `apps/integration/.../helpers`; `ChipEconomyPlayTest` is the conservation home; `MatchOverGraceDriverTest` is the driver-level template.

- `[P2]` **MP-19 — Money-path hardening (audit).** Three siblings beyond MP-13: (1) the bot-subsidy record + `bot_subsidy_payout` telemetry fire *outside* the cashout idempotency guard, so a crash-resumed cashout double-counts the payout on the Grafana budget dashboard — move them inside the `wasAlreadyApplied == false` branch; (2) `PostgresTableSessionRepository.incrementRebuy` is a non-atomic SELECT-then-UPDATE leaning entirely on the game mutex — make it `UPDATE … SET rebuy_count = rebuy_count + 1 RETURNING`; (3) `sitDown` debits raw `room.buyIn` while the engine seats at `RoomSettings.forBuyIn(buyIn).startingStack` (coerced to ≥ `MIN_BUY_IN`) — if a sub-floor buy-in ever reaches a Room (matchmaking `BuyInTier` / a future constructor bypass the HTTP floor), the seat mints the difference. Assert `buyIn == settings.startingStack`, or debit the coerced value.
  **Hints:** `DefaultTableSessionService.cashOut` (subsidy block ~214-238), `PostgresTableSessionRepository.incrementRebuy`, `sitDown` / `RoomSettings.forBuyIn`.

- `[P2]` **MP-20 — Client MP feedback + routing gaps (audit).** A submitted action that times out (10s, no ack) or is rejected surfaces **nothing** — the player sees a dead pause then silence, no "didn't send / not allowed" hint (`RemotePokerSession.submit` throws, the VM swallows with a log). Add a transient hint on `IntentTimeoutException` / `IntentRejectedException`. Also untested (regression risk): the `NextHandUnavailable` snackbar wiring, the `ReconnectFailed` terminal close (the `roomClosed` KDoc only names RoomDeleted/Rejected), the public-table `OpponentsLeft` routing, and the mid-game-joiner "dealt in next hand" → seated transition (a joiner never seated waits forever).
  **Hints:** `PlayPokerViewModel` submit handler (~628), `PlayMultiplayerFeatureEntryPoint`, `RemotePokerSession`.

- `[P0]` **MP-14 — Live countdown UI + match-over result screen (stages 1-2 shipped).** The freeze is fixed: the server resolves the heads-up bust dead-end (`MatchOverGraceDriver` → `Resolved` → room flips `Finished`) and the client routes off, so the old infinite "waiting" is now a bounded ~60s exit. What's left is the UX. Render the live rebuy countdown both roles from the `MatchOverPending(deadlineEpochMs, bustedSeatIndex)` wire event (busted: "rebuy in Ns or lose your seat" + a rebuy CTA; winner: "auto-continues in Ns"); clear it on `MatchOverCleared`; and replace the current route-off-as-`RoomDeleted` with a dedicated match-over **result screen** + a distinct `ClosedReason.MatchOver(winnerUserId)`. The events already arrive at the client socket (currently no-ops for Pending/Cleared).
  **Acceptance:** Busted player sees a live countdown + rebuy CTA; winner sees the same countdown / "auto-continues" cue; on expiry the winner sees a match-over result (not a silent pop) and is routed off; a rebuy inside the window clears the countdown and resumes play.
  **Hints:** Surface the match-over events out of `ReconnectingRoomSocket` (~line 365, currently `-> Unit`) through `GameplayFrame` → `RemotePokerSession` → the play VM. `MatchOverGraceDriverTest` covers the server resolution. Sentry CARDS-3S.

- `[P1]` **MP-16 — Fix the post-leave $0 buy-in rebound.** A $0 room is already structurally impossible: the create-form slider seeds `DEFAULT_BUY_IN` (`0c4f28a9`) and the server rejects out-of-range buy-ins (`RoomRoutes.kt` create floor). So the remaining user-visible $0 — sole human routed back to the in-room lobby after the other player leaves — is a **stale/placeholder client `Room` snapshot**, not a real room state. Since `buyIn == 0` now provably means "not a real snapshot," make the client refuse to render/stage it (retain the last known-good room rather than overwrite with a $0 one), then remove the `LobbyScreen` band-aid (`if (room.buyIn > 0)` at `LobbyScreen.kt:383`).
  **Acceptance:** After the opponent leaves, the sole-human lobby keeps showing the real stakes; no $0 ever renders; the band-aid is gone.
  **Hints:** Client room state flows through `RoomRepositoryImpl.upsertActive` → lobby state; the rebound snapshot arrives after `MemberLeft`. See backlog "$0 buy-in … after sole-human-left rebound". Case `docs/agent/feedback-cases/a3b1fc7d414444b295669d047d173ff8.md`; Sentry CARDS-3X/CARDS-3N.
