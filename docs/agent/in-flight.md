# In-flight

## fix: attribute MP hand outcomes to the local seat, not seat 0

**Problem:** Folding a multiplayer hand still credited `SHOW_*` achievements ("show a straight at showdown") even though the player never reached showdown.
**Approach:** Root cause was `PlayPokerViewModel.humanSeatIndex` hard-coded to `0`. Solo always seats the human at 0, but MP allocates any seat — so every per-hand attribution (XP, achievements, win-odds equity) was computed against seat 0's outcome. A folded human at seat 1 inherited seat 0's showdown hand. Added `PokerSessionFactory.humanSeatIndex(state)` (solo: fixed seat; remote: match `localUserId`, `-1` when not seated so a missing match yields no credit instead of crediting the wrong seat) and resolved the real seat at the two attribution sites (`handleHandEnded`, win-odds equity). Also added the belt-and-suspenders `!summary.wasFold` guard on the `Criterion.ShowAtLeast` branch per the todo.
**Reviewer notes:** This also silently fixed the win-odds tool for non-seat-0 MP humans (it was reading seat 0's scrubbed/empty hole cards and giving up). The `-1`-when-not-seated contract is the safe default for spectators. Builder test simulates an over-sharing reveal map (folded human's cards present) to prove `wasFold` still suppresses credit.

## feat: first-multiplayer-hand achievement (FIRST_HAND_MP)

**Problem:** No welcoming first-milestone before the `HANDS_100_MP` grind — finishing your first MP hand earned nothing.
**Approach:** New count-based server-witnessed id riding the existing `DefaultServerWitnessedAchievements` path: added `FIRST_HAND_MP` at threshold `1` in `COUNT_THRESHOLDS`, the client achievement definition ("Took a seat", COMMON, 🤝, MULTIPLAYER mode), and the `serverWitnessed` deny-list entry so the client grant route 403s it. Record-only — no `REWARD_PRODUCTS` entry, so no cosmetic/chips, per the todo.
**Reviewer notes:** Picked name "Took a seat" / icon 🤝 — directional copy call; reviewer can rename. Adding the threshold-1 id meant the count-based server tests now fire two achievements at count 100 and one at count 1, so I retargeted those tests (the "below threshold grants nothing" case moved to count 0) and added a dedicated count-1 test.
