# In-flight

## fix(game): clear stale per-seat action pill on new hand (GAME-33)

**Problem:** The previous hand's action badge (e.g. "Folded") lingered on a seat once the next hand was dealt, so every hand after the first showed a stale, misleading label.
**Approach:** `lastActionBySeat` was only cleared on the `HandStarted` event, which rides a flow independent of the `game_state` snapshot and can lose the race — the new-hand snapshot projects with the old pill map before the event clears it. Moved the clear into the snapshot-application path (`GameStateUpdated`), keyed on the authoritative `handNumber` change, mirroring the pre-fold retirement that already sits there for the same reason. `HandStarted` still clears too (harmless, idempotent). Older/out-of-order snapshots never reach the VM (dropped at the `RemotePokerSession` layer), so the clear only fires on genuine forward hand progression.
**Reviewer notes:** Red-first repro is `PokerScenarioMpTest.newHandSnapshot_clearsStaleActionPill_whenHandStartedLosesTheRace` (fold pill on hand 1, new-hand snapshot with no preceding HandStarted, assert pill gone). Added a `noSeatPill` assertion helper. Sentry CARDS-B1 resolved.

## feat(room): add in-game share button for the room code (ROOM-19)

**Problem:** After a game started the room code was only reachable by tapping the center cards, so mid-game invites were undiscoverable.
**Approach:** Added a share `IconButton` to the play-screen `TopBar`, wired through a new `onShareRoom` callback on `PlayPokerScreen`. Only the MP entry point supplies it (solo bots pass null → no button), and it reuses the exact lobby share plumbing — `Router.shareText` with `RoomInvite.linkForCode` and the same `lobby_in_room_share_message` copy — so the invite URL and text can't drift from the lobby's. New DS `Icons.Share` and a `room_top_bar_share_a11y` string. I chose reusing the lobby string over a new room-namespaced duplicate to keep one source of voice; the alternative (a `room_*` copy) would have been cleaner by naming convention but risked the two invite messages drifting.
**Reviewer notes:** New preview `PlayPokerScreenPreview_MpWithShare` renders the button. QA sub-bullet added under MP-1B. ID ROOM-19 is fresh (prior max was ROOM-18). Sentry CARDS-B3 (owner request) resolved.
