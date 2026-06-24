# In-flight

## fix(lobby): full-screen retry state for a failed room creation

**Problem:** When `rooms.createRoom(...)` failed, the lobby left the user on the "Setting up your table…" spinner with a small red error line and no way forward — the create surface had already popped (CARDS-2E).
**Approach:** Added a `LobbyState.createError` derivation (a Create* error with no room and not mid-create) that drives a new full-screen `CreateErrorContent` in `LobbyScreen` — title + message + a primary **Try again** (re-fires `LobbyAction.CreateRoom`, which the VM can do because it still holds the create args) + a secondary **Go back**. Chose in-place retry over routing back to the popped `PrivateCreateRoute` because the args are already captured and retry is the common intent; the join-error fix (CARDS-28) routes back only because a bad *code* must be re-typed, which doesn't apply to create. Aligns visually with `BlockingErrorScreen`.
**Reviewer notes:** Retry shows the spinner again while in-flight (`createError` goes null when `creating` flips true) then re-renders the error if it fails again — intended. Not exercised by a Compose UI test (none in this module yet); covered by VM-level tests on the `createError` derivation. The "room not found" join treatment stays inline as before — only the create paths get the full-screen state.
