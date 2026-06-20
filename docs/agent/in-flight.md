# In-flight

## fix: send a real room leave when a player leaves an MP table

**Problem:** `PlayPokerAction.LeaveTable` never told the server the player left — it only fired the bot-mode review prompt — so a player who left an MP game kept their seat/membership on the server (found in the 2026-06-19 playtest).
**Approach:** Added `PokerSession.leave()` (remote impl fires the durable `roomRepository.leaveRoom(code)` via a lambda the factory wires in; local-bots no-ops). The VM calls it on `LeaveTable` parented to `AppCoroutineScope`, not `viewModelScope`, because the screen pops the VM the instant it fires the action and the leave must still reach the server (AGENTS.md "fire-and-forget actions outlive the screen"). Chose a lambda over injecting `RoomRepository` + room code into the session so `RemotePokerSession` stays transport-decoupled.
**Reviewer notes:** This ships the "leaver actually leaves" half only. The other two parts of the original todo (remaining players see the seat disappear in-game; last-human-left → dialog → Home) both need the server to fold/remove the seat from the in-progress `GameSession` state on leave — leaving the room frees the *membership* row but not the *game* seat, which overlaps the B3 forfeit-then-spectator policy. I rewrote the todo bullet to describe that remaining gap rather than deleting it.
