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

### Multiplayer & rooms

- `[P2]` **MP-17 — Remaining MP terminal edge cases (the all-in-leaver chip burn is fixed).** Shipped + tested: an all-in player who leaves mid-hand is settled at their real resolved stack, never burned — whether the opponent stays (`deferSettlementIfAllInLive` + `departedSettlements` on showdown) or both vanish (`RoomTeardownCoordinator` resolves the live hand + cashes out every open session via `activeUsersInRoom`). Covered by `MatchOverEdgeCasesTest` (single-leaver, both-leave, multi-way bust) + `GameEngineForfeitTest.forfeitFacingAllIn…`. **Still untested (lower priority):** 3→2→1 staged collapse, and the reaper grace expiring on a player who's mid-hand all-in (chip-conservation through the reaper path — `ReaperGraceExpiryPlayTest` covers seat-freeing/host-migration but never reaps a player committed all-in in a live hand). Demoted to P2 — the money-losing cases are closed; these remaining ones are lifecycle-correctness checks expected to already pass.
  **Hints:** `MatchOverEdgeCasesTest` is the template (`driveActions` + `stackedDeck` for multi-way).

- `[P2]` **MP-20 — Client MP feedback + routing gaps (audit).** A submitted action that times out (10s, no ack) or is rejected surfaces **nothing** — the player sees a dead pause then silence, no "didn't send / not allowed" hint (`RemotePokerSession.submit` throws, the VM swallows with a log). Add a transient hint on `IntentTimeoutException` / `IntentRejectedException`. Also untested (regression risk): the `NextHandUnavailable` snackbar wiring, the `ReconnectFailed` terminal close (the `roomClosed` KDoc only names RoomDeleted/Rejected), the public-table `OpponentsLeft` routing, and the mid-game-joiner "dealt in next hand" → seated transition (a joiner never seated waits forever).
  **Hints:** `PlayPokerViewModel` submit handler (~628), `PlayMultiplayerFeatureEntryPoint`, `RemotePokerSession`.

- `[P1]` **MP-16 — Fix the post-leave $0 buy-in rebound.** A $0 room is already structurally impossible: the create-form slider seeds `DEFAULT_BUY_IN` (`0c4f28a9`) and the server rejects out-of-range buy-ins (`RoomRoutes.kt` create floor). So the remaining user-visible $0 — sole human routed back to the in-room lobby after the other player leaves — is a **stale/placeholder client `Room` snapshot**, not a real room state. Since `buyIn == 0` now provably means "not a real snapshot," make the client refuse to render/stage it (retain the last known-good room rather than overwrite with a $0 one), then remove the `LobbyScreen` band-aid (`if (room.buyIn > 0)` at `LobbyScreen.kt:386`).
  **Acceptance:** After the opponent leaves, the sole-human lobby keeps showing the real stakes; no $0 ever renders; the band-aid is gone.
  **Hints:** Client room state flows through `RoomRepositoryImpl.upsertActiveRoom` → lobby state; the rebound snapshot arrives after `MemberLeft`. See backlog "$0 buy-in … after sole-human-left rebound". Case `docs/agent/feedback-cases/a3b1fc7d414444b295669d047d173ff8.md`; Sentry CARDS-3X/CARDS-3N.
