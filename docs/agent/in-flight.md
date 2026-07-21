# In-flight

## fix(game): clear stale per-seat action pill on new hand (GAME-33)

**Problem:** The previous hand's action badge (e.g. "Folded") lingered on a seat once the next hand was dealt, so every hand after the first showed a stale, misleading label.
**Approach:** `lastActionBySeat` was only cleared on the `HandStarted` event, which rides a flow independent of the `game_state` snapshot and can lose the race — the new-hand snapshot projects with the old pill map before the event clears it. Moved the clear into the snapshot-application path (`GameStateUpdated`), keyed on the authoritative `handNumber` change, mirroring the pre-fold retirement that already sits there for the same reason. `HandStarted` still clears too (harmless, idempotent). Older/out-of-order snapshots never reach the VM (dropped at the `RemotePokerSession` layer), so the clear only fires on genuine forward hand progression.
**Reviewer notes:** Red-first repro is `PokerScenarioMpTest.newHandSnapshot_clearsStaleActionPill_whenHandStartedLosesTheRace` (fold pill on hand 1, new-hand snapshot with no preceding HandStarted, assert pill gone). Added a `noSeatPill` assertion helper. Sentry CARDS-B1 resolved.
