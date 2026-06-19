# In-flight (worker handoff)

## feat(mp): show opponent level at the table

**Problem:** MP opponents always render at level 0 — `RemotePokerSessionFactory.occupantsFor` hardcoded `level = 0` and `TableUiState.badgeFor` returned `null` for remote humans, because no XP/level ever reached the client over the wire.

**Approach:** Mirrored the existing avatar/badge path exactly. Added `xp: Long?` to the shared engine `Seat` (and server `SeatOccupant`); `RoomSocketRoutes.handleStartHand` resolves each member's `ProgressionRepository.find(userId)?.totalXp` at hand-start (same site that resolves badges), rides it onto the `Seat`, and `GameSession.requestNextHand` preserves it across hands. Client derives the level locally via `levelProgressFor(seat.xp).level` in `badgeFor` + `occupantsFor`. **Direction call:** level **freezes per session** (like badges) rather than re-resolving on `RequestNextHand` — the alternative would thread `ProgressionRepository` into the registry's next-hand path purely for a cosmetic pill tick; not worth the coupling for V1 (rationale + alternatives in `decisions.md` 2026-06-19). Sent **raw XP** over the wire, not a derived level Int, so the client keeps the single source of truth on the curve.

**Reviewer notes:** Needs a server deploy to populate XP — pre-deploy, `find` returns null and the pill omits gracefully (no regression). The `progressionRepository` param was threaded through `roomSocketRoutes` → `handleClientFrame` → `handleStartHand` and wired in `Application.kt`; the in-process integration harness + server test support got no-op/fixed-XP `ProgressionRepository` fakes. The shared `seatToOccupant` helper (solo path) also now derives level from `seat.xp` for consistency, though solo only seats the local human (whose level comes via the `humanLevel` path), so it's a latent-correctness change, not a behavior change today.

## test(mp): pin that public seat fields reach an opponent's view

**Problem:** B6 gap — a new public `Seat` field that doesn't ride the wire (or one the scrub accidentally drops) would ship as a silently-missing avatar/badge/level on a real opponent's screen, with no test catching it. (The avatar bug from the prior cycle was exactly this shape.)

**Approach:** Extended `WireFormatContractTest` (`:apps:integration`) with a field-by-field assertion: build a `GameState` whose opponent seat populates every public field (+ hole cards), `scrubbedFor(viewer)`, round-trip through the shared `GameState` serializer (the same bytes both sides decode), and assert each public field survives on the opponent's decoded seat while hole cards are scrubbed. The client `RoomSocketEventDto` is `internal`, so the test exercises the shared `GameState`/`Seat` serializer + `scrubbedFor` directly rather than the frame wrapper — that's where the "forgot to send X" risk actually lives.

**Reviewer notes:** None. Depends on the `Seat.xp` field from the commit above (the test asserts `xp` survives).

## test(mp): cover raise / late-fold / all-in / multi-hand seams over the wire

**Problem:** B6 "buff the seams" — the integration suite only drove passive call/check + a preflop fold. Every recent MP bug lived at the wire, yet aggressive lines, late-street folds, all-ins, and hand-to-hand continuity (button rotation, stack carry-over) had no end-to-end coverage.

**Approach:** Added three integration test classes against the in-process server + two real sockets, plus two reusable drivers in `PlayHelpers.kt` (`advancePassivelyUntil(street)` / `playPassivelyToCompletion()`). Coverage: (a) a preflop **raise** is accepted, applied (`currentBetThisStreet` reaches the raised total on both clients), and the called-down hand reaches a five-card showdown with chips conserved; (b) a **turn fold** ends the hand and awards the pot to the non-folder; (c) across two hands the **button rotates** and stacks **carry over** (proven via `stack + contributedThisHand == priorEndStack`, so it can't be a silent reset); (d) a heads-up **all-in + call** runs the board out and settles winner-take-all/chop (each stack ∈ {0, start, 2×start}). **Direction call:** `playPassivelyToCompletion` acts on the raise responder *directly* before entering the passive loop — the forward-cursor `nextSnapshot` would otherwise stall waiting for a newer snapshot than the one I'd already read to assert the raise applied.

**Reviewer notes:** All four assert in production order (each `nextSnapshot` reads strictly after the action via the session's forward cursor). The all-in test relies on equal starting stacks making a single main pot — that's the only-no-side-pot invariant the assertion encodes.

**Deferred:** True 3-player **side-pot** settlement over the wire (sub-part d's harder half) — needs a 3-client table + unequal stacks. Rewrote the `docs/todo.md` bullet down to exactly that remaining gap.

## test(mp): pin action pills survive event/snapshot ordering

**Problem:** B6 sub-part (e) — the "Called 50" / "Folded" pill below a seat is a per-hand transient the VM derives from `ActionTaken` events, not from `GameState`. Snapshots and events arrive on two independent flows with no ordering guarantee; the winner-rendering path had ordering regression tests but the action-pill path didn't, so a snapshot-less `ActionTaken` (or a `StreetAdvanced` that should clear pills) could silently drop/strand a pill.

**Approach:** Two `PlayPokerViewModelTest` cases driving the `FakePokerSession`: emit a snapshot, then an `ActionTaken` with **no following snapshot**, and assert the seat's `lastAction` pill renders; then a `StreetAdvanced` (again snapshot-less) clears it. Mirrors the existing `handEndedEvent_*Snapshot_rendersWinner` regression shape, one layer down on the per-seat projection.

**Reviewer notes:** None.
