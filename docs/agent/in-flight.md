# In-flight

## fix(gameplay): scrub undealt deck from broadcast state (MP-31)

**Problem:** `GameState.deckRemaining` — the exact future flop/turn/river, with no burn cards — was serialized to every seated client on each state update; `scrubbedFor()` only emptied other seats' hole cards, so a modified client could read the whole runout.
**Approach:** `scrubbedFor()` now always empties `deckRemaining` alongside the hole-card scrub. Verified only the server-side `GameEngine` reads `deckRemaining` (from its own authoritative state); nothing on the socket/client path consumes it, and solo `LocalBotsSession` builds its own deck. Added two regression tests asserting the scrubbed deck is empty preflop and at showdown.
**Reviewer notes:** None. The engine keeps operating on the un-scrubbed authoritative state, so gameplay is unaffected.
