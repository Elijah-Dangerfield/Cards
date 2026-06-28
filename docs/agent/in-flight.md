# In-flight (this cycle)

Reviewer reads this to write the PR. One block per commit.

---

## fix(lobby): reconcile wallet on leave for real-chip rooms (MP-27)

**Problem:** After an opponent-left kick collapsed the play screen back to the lobby, leaving the lobby left the buy-in showing as escrowed until a foreground/background forced a `/wallet/sync` — the post-kick lobby exit landed Home on a stale balance.
**Approach:** `LobbyViewModel.Leave` now fires a one-shot, fire-and-forget `chips.sync()` (on `AppCoroutineScope` so it survives the screen pop) for any real-chip room (`Room.buyIn > 0`) before the `leaveRoom` POST, mirroring the play VM's `reconcileWalletAfterGame`. Free tables (`buyIn == 0`) skip it — nothing was escrowed. Kept it a silent sync rather than replicating the play screen's celebratory credit confirmation, which is intentionally play-screen-only.
**Reviewer notes:** Two new tests pin both branches (real-chip leave syncs once; free table doesn't). The fix is scoped to the explicit Leave path per the feedback-case diagnosis; if the kick ever lands the user on a *closed* lobby socket without an explicit Leave, the `walletReconciled` latch is already in place to extend the same reconcile to a close-under-us path. Server balance was already correct — this is client-display only.

---

## fix(room): present hand result off a terminal Complete snapshot (MP-26)

**Problem:** Heads-up, when the opponent times out / folds preflop, the non-acting player's client receives only the terminal `game_state street=Complete acting=null` snapshot — no `ActionTaken(Fold)` / `HandEnded` / `PotAwarded` — so `handResult` stayed null and the whole hand-over UI (winner banner + Next Hand) is gated on it: the table froze with no acting seat, no winner, no next-hand path.
**Approach:** `TableUiState.fromGameState` now synthesizes a `HandResultView` when `street == Complete` and no `HandEnded` event arrived, deriving the winner(s) from the snapshot's `Pot.eligibleSeatIndexes` (falling back to the still-in-hand seats if the pots were already scrubbed), with `byFold` inferred from whether a single contender remained. This is the same MP-25 family ("drive presentation off the Complete snapshot") extended from card-reveal to the winner/Next-Hand presentation, so it also closes the gap where a showdown Complete snapshot without `HandEnded` showed cards but no winner banner.
**Reviewer notes:** Chose snapshot-synthesis in the projection (one place, covers every Complete-without-HandEnded path) over having `RemotePokerSession` fabricate a synthetic `HandEnded` event (would have to invent a sequence number and risk double-counting XP/stats, which key off the real event). The synthesized winner amount is `potTotal / winners` — fine for the banner, but it is NOT wired to XP/stat credit (those still only fire on a real `HandEnded`, by design — we don't want to award off a possibly-incomplete snapshot). Red→green via `PokerScenarioMpTest.foldCompleteSnapshotWithoutHandEndedEvent_stillPresentsTheWinner`.

---

## fix(rooms): keep discovering tables while genuinely waiting (ROOM-12)

**Problem:** When the first `/candidates` browse was empty, `PublicSearchingViewModel` seated the user into its own fresh waiting table and never browsed `/candidates` again (the re-poll was gated on `SearchPhase.Choosing`). Two people who started searching seconds apart sat in two separate waiting tables forever and never matched.
**Approach:** `beginSearch` now arms a wait-time candidates poll (reusing `candidatesPollJob`). On each poll, while still genuinely waiting alone, the VM picks a migration target — skipping its own table, preferring more humans, then the *older* table (code as final tiebreak) — and consolidates into it (leave own seat on `appScope`, join the target). The age tiebreak guarantees that of two mutual searchers exactly one migrates, so they never swap seats and both end up alone. The poll self-terminates the moment a human arrives or the window flips to the bot offer.
**Approach (alternative rejected):** Could instead have the server fold a later `find` into an existing waiting table (case suggested either). Chose the client poll because it's self-contained, directly closes the confirmed VM gap, and is fully testable in the existing harness; the server-side dedupe is a larger, separate change. Flagging the direction for review.
**Reviewer notes:** `/v1/matchmaking/candidates` includes the caller's own table (no self-exclusion server-side), so the migration filter explicitly drops `ownWaitingRoom.code` — there's a regression test for "never migrate to our own table." Three new tests: migrate-to-older, don't-migrate-to-own, don't-migrate-to-newer. NOTE: the wait poll is a `while` loop gated on the live wait state — it had to be state-gated (not `while(true)`) so it self-drains; an unbounded version hung `runTest`. If a future edit reintroduces `while(true)`, the search tests will hang.

---

## feat(rooms): lower create-table default buy-in to 1,000 (ROOM-13)

**Problem:** Owner review asked whether the create-table screen's default buy-in/blinds/stakes are sensible for a first-time host. They weren't: the default was 5,000 — half of a new player's entire 10,000-chip starter grant on one table.
**Approach:** Added `RoomSettings.DEFAULT_HOST_BUY_IN = 1_000` (≈10% of the grant, 100 BB at 5/10 blinds) and pointed the create screen's initial buy-in at it, distinct from the protocol-level `DEFAULT_BUY_IN = 5_000` (still the server's omit-a-buy-in fallback + matchmaking snap target). Max players (6) and Open-to-anyone (off) were reviewed and left as-is. The slider still drags to the full balance, so nothing is lost for a player wanting higher stakes.
**Reviewer notes:** Directional call on the exact number — chose 10% over 25% (2,500) to leave the most room for the rebuy + second-table loop a new player is likeliest to want; a test pins it to the 5..25%-of-grant band so a future bump stays principled. Decision logged in `docs/decisions.md` (2026-06-28). This is the full ROOM-13 scope — the blinds already derive correctly from buy-in via `forBuyIn`, so there was nothing to fix there.
