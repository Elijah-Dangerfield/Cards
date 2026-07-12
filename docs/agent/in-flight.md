# In-flight log

## docs(wiki): progression + achievements pages match PROG-12 re-pull (ENG-32)

**Problem:** `docs/wiki/progression.md` and `achievements.md` still described pre-PROG-12 reward timing — server-minted chips becoming visible only on the next balance overwrite / trigger edge.
**Approach:** Rewrote the reward-visibility wording on both pages to the as-built contract: minting endpoints return `walletBalance` only when they actually minted, and the client reacts with an immediate `ChipsRepository.sync()` re-pull, never a direct apply. Both pages now link to wallet.md for the ordering argument rather than duplicating it.
**Reviewer notes:** Verified against `ProgressionRepositoryImpl.sync` (walletBalance branch ~224) and `AchievementRepositoryImpl.sync` (~194) rather than trusting the todo text. None surprising.
