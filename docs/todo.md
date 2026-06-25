# TODO

**Last reviewed:** 2026-06-25 (decisions pass: every item made worker-pickable; BILL-1 → developer-todo) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## A. UX gaps

### Progression & stats

- `[P1]` **PROG-1 — Make the achievement engine server-authoritative over player stats.** `AchievementRepositoryImpl` accumulates its own per-id progress via local `AchievementDao` counters, so bars reset on account-switch / reinstall. Expand the server stats so it computes/stores every counter the predicates need (reshape `PlayerStatsDto` or add a dedicated achievements-stats endpoint — whichever is cleanest), convert the client predicates to read that snapshot, and record a `claimed_at_value` per achievement so each unlocks once. ~20 counters need server backing today (pot high-water marks, comeback/recovery, good-fold, all-in, doubles/triples, busts-dealt, the 9 hand-strength shows, level) — the rest (`handsPlayed`, no-bust streak, `perBotWins`) already exist on `PlayerStats`.

  **Acceptance:** Sign in on a second device → correct progress after one sync; achievement bars agree with the stats screen.

  **Hints:** Land server schema + endpoint and the client conversion together, achievement-unlock tests green. Server: `apps/server/.../PlayerStatsRepository.kt`, `PlayerStatsDto.kt`, a migration. Client: `AchievementRepositoryImpl.kt`, `AchievementRegistry.kt`, `PlayerStats.kt`.

### Multiplayer & rooms

- `[P1]` **MP-13 — Persist last-known stack so the crash-recovery sweep can't mint.** The live mint is fixed: a busted-and-dropped player who leaves (or is torn down) is now cashed out their real 0 via `GameSession.lastKnownStack`, not refunded their escrow — and because the leave + teardown paths now derive the same value, the concurrent-cashout race resolves correctly too (`Mp13ConservationTest` proves it over the wire). One edge remains: `lastKnownStacks` is in-memory, so after a server crash the boot recovery sweep (`DefaultTableSessionRecoverySweep`) rehydrates only the snapshot — which has no seat for a busted-dropped player — and still falls through to a full-escrow refund. Persist the last-known stack in the session snapshot (or the `table_sessions` row) so the sweep reads it too.
  **Acceptance:** a crash mid-game with a busted-and-dropped player, then the boot sweep, cashes them out 0 (no mint). Test via the harness `restart()` + the sweep.
  **Hints:** `DefaultTableSessionRecoverySweep` (reads `snapshots.readByCode`), `SessionSnapshot` (persist `lastKnownStacks` alongside the `GameState`), `GameSession`. Was P0 (the live mint); demoted now the common path is fixed + tested. Case `docs/agent/feedback-cases/7b9fada4e2364ed6971fffef505ec57b.md`; Sentry CARDS-3V.

- `[P1]` **MP-17 — Remaining MP terminal edge cases (the common all-in-leaver burn is fixed).** Shipped + tested: an all-in player who leaves mid-hand while the opponent stays no longer burns their won pot — `GameSession.deferSettlementIfAllInLive` defers their cash-out and `departedSettlements` settles their resolved stack on showdown (`MatchOverEdgeCasesTest.allInWinnerLeavesMidHand…`). Also green: multi-way bust to one survivor (ruling out the audit's "zero-survivor wedge" — conservation always leaves a survivor), and the engine resolving a heads-up forfeit-facing-an-all-in (`GameEngineForfeitTest.forfeitFacingAllIn…`). **Still open:**
  - **Both clients vanish mid-all-in → settles only via the boot sweep, not in-session.** With no live socket left, nothing processes the second leave's forfeit (which resolves the hand) or collects the departed leaver's `departedSettlements`, so the deferred session is orphaned until the boot recovery sweep. Close it by resolving the live hand + settling pending leavers from `RoomTeardownCoordinator` (it owns the wallet at room close). Confirm the sweep doesn't mint a stale-stack refund in the meantime (MP-13 territory).
  - **Untested:** 3→2→1 staged collapse, reaper grace expiring mid-hand.
  **Hints:** `RoomTeardownCoordinator` (+ `GameSession.pendingSettlementIds` / `lastKnownStack`), `MatchOverEdgeCasesTest` is the template (see the both-leave NOTE in that file).

- `[P2]` **MP-20 — Client MP feedback + routing gaps (audit).** A submitted action that times out (10s, no ack) or is rejected surfaces **nothing** — the player sees a dead pause then silence, no "didn't send / not allowed" hint (`RemotePokerSession.submit` throws, the VM swallows with a log). Add a transient hint on `IntentTimeoutException` / `IntentRejectedException`. Also untested (regression risk): the `NextHandUnavailable` snackbar wiring, the `ReconnectFailed` terminal close (the `roomClosed` KDoc only names RoomDeleted/Rejected), the public-table `OpponentsLeft` routing, and the mid-game-joiner "dealt in next hand" → seated transition (a joiner never seated waits forever).
  **Hints:** `PlayPokerViewModel` submit handler (~628), `PlayMultiplayerFeatureEntryPoint`, `RemotePokerSession`.

- `[P0]` **MP-14 — Live countdown UI + match-over result screen (stages 1-2 shipped).** The freeze is fixed: the server resolves the heads-up bust dead-end (`MatchOverGraceDriver` → `Resolved` → room flips `Finished`) and the client routes off, so the old infinite "waiting" is now a bounded ~60s exit. What's left is the UX. Render the live rebuy countdown both roles from the `MatchOverPending(deadlineEpochMs, bustedSeatIndex)` wire event (busted: "rebuy in Ns or lose your seat" + a rebuy CTA; winner: "auto-continues in Ns"); clear it on `MatchOverCleared`; and replace the current route-off-as-`RoomDeleted` with a dedicated match-over **result screen** + a distinct `ClosedReason.MatchOver(winnerUserId)`. The events already arrive at the client socket (currently no-ops for Pending/Cleared).
  **Acceptance:** Busted player sees a live countdown + rebuy CTA; winner sees the same countdown / "auto-continues" cue; on expiry the winner sees a match-over result (not a silent pop) and is routed off; a rebuy inside the window clears the countdown and resumes play.
  **Hints:** Surface the match-over events out of `ReconnectingRoomSocket` (~line 365, currently `-> Unit`) through `GameplayFrame` → `RemotePokerSession` → the play VM. `MatchOverGraceDriverTest` covers the server resolution. Sentry CARDS-3S.

- `[P1]` **MP-16 — Fix the post-leave $0 buy-in rebound.** A $0 room is already structurally impossible: the create-form slider seeds `DEFAULT_BUY_IN` (`0c4f28a9`) and the server rejects out-of-range buy-ins (`RoomRoutes.kt` create floor). So the remaining user-visible $0 — sole human routed back to the in-room lobby after the other player leaves — is a **stale/placeholder client `Room` snapshot**, not a real room state. Since `buyIn == 0` now provably means "not a real snapshot," make the client refuse to render/stage it (retain the last known-good room rather than overwrite with a $0 one), then remove the `LobbyScreen` band-aid (`if (room.buyIn > 0)` at `LobbyScreen.kt:383`).
  **Acceptance:** After the opponent leaves, the sole-human lobby keeps showing the real stakes; no $0 ever renders; the band-aid is gone.
  **Hints:** Client room state flows through `RoomRepositoryImpl.upsertActive` → lobby state; the rebound snapshot arrives after `MemberLeft`. See backlog "$0 buy-in … after sole-human-left rebound". Case `docs/agent/feedback-cases/a3b1fc7d414444b295669d047d173ff8.md`; Sentry CARDS-3X/CARDS-3N.
