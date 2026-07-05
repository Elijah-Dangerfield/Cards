# In-flight log

## docs(wiki): describe as-built bot behavior, drop unshipped claims (ENG-19)

**Problem:** `docs/wiki/bots.md` presented unbuilt "V1 countermeasures" as shipped: a provably-fair SHA-256 deck commit (no implementation anywhere), showdown equity transparency and bot-thought hand-history replay (`BotThought` never leaves think-delay pacing), and "Casual bots make only pot-odds-positive calls" (the curiosity-call branch calls without pot odds at every tier).
**Approach:** Restructured the page into "What's built" (tiers, difficulty-gated opponent modeling/tactics, casual no-bluff, the curiosity-call caveat, BotThought's actual usage) and a "Not implemented" section that keeps the three aspirational surfaces visible as candidate follow-ups rather than deleting the ideas. Also fixed `docs/wiki/client-patterns.md`: the shop deep-link call is `bus.requestScrollTo(ShopCategory.Avatars)` and the bus lives in the `:features:shop` api, not `:libraries:cards`.
**Reviewer notes:** All claims verified against `BotDecision.kt`, `BotPersonality.forDifficulty`, `ServerBotDriver.thinkDelay`, and a repo-wide grep for SHA-256/deck-commit code. "Not implemented" framing (vs. outright deletion) was a judgement call — the fairness roadmap context seemed worth keeping.

## style(ui): route Banner's icon well through Radii.Callout (ENG-20)

**Problem:** `Banner.kt` clipped the leading icon well with a literal `RoundedCornerShape(14.dp)`, the last literal-corner callsite in non-preview `:libraries:ui` component code.
**Approach:** Swapped to `Radii.Callout.shape` (R600 = 14dp, so rendering is identical) rather than minting a new `IconWell` token — one callsite doesn't justify a new semantic alias, and Callout already reads right for a small inner tile.
**Reviewer notes:** `BaseBottomSheet.kt` still holds `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)` as a named private constant — top-corners-only geometry the all-corner `Radius` token can't express, so I left it (the todo's problem statement also treated Banner as the last offender). Flagging in case you want a partial-corner token instead.
