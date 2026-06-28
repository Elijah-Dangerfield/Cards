# In-flight

## fix(room): keep deal/reveal animations in real-chip games (GAME-8)

**Problem:** The unified global "Game speed" setting (`GameSpeed.animationScale`) paces solo practice, but it also drove a real bots-for-chips public game to Instant — a tester lost the card flips in a real game and intuited the speed should be practice-only.
**Approach:** Scoped the animation pacing, not the setting. Added `PlayPokerState.effectiveTableSpeed` (and a `realChipsAtStake` helper) that forces `GameSpeed.Normal` whenever real chips are on the line — a human MP game (`isRealMultiplayer`) *or* the public subsidized disclosed-bot table (`subsidizedBotTable`). `PlayPokerScreen` provides `TableTempo(state.effectiveTableSpeed)` instead of the raw `gameSpeed`. The setting still tunes solo + private practice-bot tables. Chose this over making the whole setting practice-only (would silently ignore a user's explicit Instant in practice MP, which is fine to honour) and over a per-animation minimum floor (more surface area, same outcome).
**Reviewer notes:** The reporter's room was a public bots-for-chips table, which is `practiceTierBotsOnly == true` — so `isRealMultiplayer` alone would NOT have covered it; `realChipsAtStake` adds the `subsidizedBotTable` arm specifically for that case. Bot think-time scaling (`botThinkScale`) is untouched and irrelevant here — these tables are server-driven. Test is `EffectiveTableSpeedTest` (4 cases: solo honours, human-MP forces Normal, subsidized-bot forces Normal, private-practice-bot honours).
**Deferred:** None.
