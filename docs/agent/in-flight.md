# In-flight

## feat(room): notice for a mid-game joiner waiting to be dealt in

**Problem:** Joining an in-progress public/MP table seated the player as a seatless spectator with no indication they were waiting for the next hand boundary — the table read as stuck/empty (feedback CARDS-22).
**Approach:** A seatless local member is the signal — `RemotePokerSessionFactory.tableFor` already derives `humanSeatIndex = -1` for a mid-hand joiner, so I plumb a `waitingToBeDealtIn` flag onto `TableUiState.Active` (set only when `humanSeatIndex < 0`, so solo sessions — always seated — never trip it) and render a slim "You'll be dealt in next hand" notice under the top bar, mirroring the existing `PracticeTierLabel`. Chose this over a new server frame because the seatless snapshot the server already sends is a sufficient, self-clearing signal — the notice disappears the moment the next hand seats them.
**Reviewer notes:** This is the client half of the CARDS-22 pairing; the server-side B7 stall (the joiner actually being dealt in) is untouched and still open in `docs/todo.md`. The notice will also show briefly for any other seatless-member case (e.g. a forfeited member downgraded to spectator) — which is correct framing, not a bug. Covered by two `RemotePokerSessionFactoryTest` assertions + a screen preview.
