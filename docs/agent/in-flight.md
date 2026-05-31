# In-flight

## test(room): pin RemotePokerSessionFactory projection logic (B6 Round 1)

**Problem:** B6 Round 1's most critical addition — `RemotePokerSessionFactory`'s seat-derivation/projection logic, the load-bearing seam for every MP action submission — shipped with zero coverage.
**Approach:** Completed `RemotePokerSessionFactoryTest` (occupants Human/Bot/Empty derivation, `tableFor` Loading sentinel, local-user-by-id seat lookup at any index, observer/not-seated case, MP labels, `create` opens the connection) and added the two missing checkboxes — `tableFor_userReseats_pickedUpInNextProjection` (dynamic-lookup invariant) and a `bootstrap` test that drives `session.run()` and asserts a pushed `StateSnapshot` frame reaches `gameStateFlow`. All 10 RemotePokerSessionFactory checkboxes in `testing-plan.md` are now ticked.
**Reviewer notes:** The bootstrap test name in the file is `bootstrap_drivesSession_routingSnapshotFramesIntoState` (behavioral assertion) rather than the plan's `bootstrap_callsSessionRun` — verifying the run loop's observable effect is stronger than asserting the call. Run via `./gradlew :features:room:impl:testDebugUnitTest --tests "*RemotePokerSessionFactoryTest"`.
**Deferred:** The `LobbyViewModel` half of Round 1 (13 checkboxes) is the remaining Round-1 work — picked up as a separate commit this cycle.
