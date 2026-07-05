# In-flight log

## docs(wiki): describe as-built bot behavior, drop unshipped claims (ENG-19)

**Problem:** `docs/wiki/bots.md` presented unbuilt "V1 countermeasures" as shipped: a provably-fair SHA-256 deck commit (no implementation anywhere), showdown equity transparency and bot-thought hand-history replay (`BotThought` never leaves think-delay pacing), and "Casual bots make only pot-odds-positive calls" (the curiosity-call branch calls without pot odds at every tier).
**Approach:** Restructured the page into "What's built" (tiers, difficulty-gated opponent modeling/tactics, casual no-bluff, the curiosity-call caveat, BotThought's actual usage) and a "Not implemented" section that keeps the three aspirational surfaces visible as candidate follow-ups rather than deleting the ideas. Also fixed `docs/wiki/client-patterns.md`: the shop deep-link call is `bus.requestScrollTo(ShopCategory.Avatars)` and the bus lives in the `:features:shop` api, not `:libraries:cards`.
**Reviewer notes:** All claims verified against `BotDecision.kt`, `BotPersonality.forDifficulty`, `ServerBotDriver.thinkDelay`, and a repo-wide grep for SHA-256/deck-commit code. "Not implemented" framing (vs. outright deletion) was a judgement call — the fairness roadmap context seemed worth keeping.
