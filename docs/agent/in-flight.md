# In-flight

## test(room): pin RemotePokerSessionFactory projection logic (B6 Round 1)

**Problem:** B6 Round 1's most critical addition — `RemotePokerSessionFactory`'s seat-derivation/projection logic, the load-bearing seam for every MP action submission — shipped with zero coverage.
**Approach:** Completed `RemotePokerSessionFactoryTest` (occupants Human/Bot/Empty derivation, `tableFor` Loading sentinel, local-user-by-id seat lookup at any index, observer/not-seated case, MP labels, `create` opens the connection) and added the two missing checkboxes — `tableFor_userReseats_pickedUpInNextProjection` (dynamic-lookup invariant) and a `bootstrap` test that drives `session.run()` and asserts a pushed `StateSnapshot` frame reaches `gameStateFlow`. All 10 RemotePokerSessionFactory checkboxes in `testing-plan.md` are now ticked.
**Reviewer notes:** The bootstrap test name in the file is `bootstrap_drivesSession_routingSnapshotFramesIntoState` (behavioral assertion) rather than the plan's `bootstrap_callsSessionRun` — verifying the run loop's observable effect is stronger than asserting the call. Run via `./gradlew :features:room:impl:testDebugUnitTest --tests "*RemotePokerSessionFactoryTest"`.
**Deferred:** The `LobbyViewModel` half of Round 1 (13 checkboxes) is the remaining Round-1 work — picked up as a separate commit this cycle.

## fix(lobby): emit HostPromoted from the applied snapshot + cover new MP paths

**Problem:** B6 Round 1's `LobbyViewModel` MP-path gaps — `startGame` host/non-host/alone gating, non-host gameplay-snapshot auto-follow, `effectiveHostUserId` promotion derivation, and the `HostPromoted` banner — shipped with zero coverage (13 checkboxes).
**Approach:** Added the 13 `LobbyViewModelTest` cases. Writing the `connectionUpdated_hostChanges` test surfaced a real bug: the handler re-read `state.effectiveHostUserId` immediately after `updateState`, but the derived `stateFlow` lags the mutable source by a dispatch, so `newHost` was stale and the promotion banner never fired (would repro in production on real `Dispatchers.Main` too — Unconfined is strictly more eager). Fixed by capturing the applied snapshot inside `updateState` (`.also { appliedState = it }`) and keying the comparison off that instead of a possibly-stale read.
**Reviewer notes:** This is a behavioral fix to shipped MP code, so the commit is `fix:` not `test:`. The fix is the minimal capture-what-you-wrote pattern; an alternative (derive the new host purely from `action.connection`) was rejected because Closed/Cancelled transitions also feed host state and the snapshot-capture covers them uniformly. Round 1 of `testing-plan.md` is now fully ticked (10 + 13). Pre-existing `last.room!!` warning in the prefilled-join test left untouched (out of scope).
**Deferred:** Rounds 2–6 of the testing plan remain — todo §B6 hint updated to point at Round 2/3/4 next.
