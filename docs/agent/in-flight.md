# In-flight (this cycle)

## feat(profile): shrink profile achievement medals to Home size (PROG-7)

**Problem:** Profile-screen achievement medals render larger than Home's recent-achievements strip (they filled their grid column), so the two surfaces read inconsistently.
**Approach:** The profile `AchievementsSection` now pins each medal to `MedalSize.Small` (88dp — the same preset Home's `RecentAchievementsStrip` uses) instead of `fillMaxWidth()`; the medal stays centered in its 3-up column. One shared size token drives both surfaces, so they can't drift.
**Reviewer notes:** On a very narrow phone the fixed 88dp is slightly smaller than the old fill-column size; that's the intended "smaller, matches Home" direction. No test — pure sizing tweak; the previews (`ProfileScreenPreview*`) cover the render.

## fix(profile): suppress acquisition line on default cosmetics (SHOP-4)

**Problem:** The default felt + card back render an "Earned"/"Bought free … ago" line in their detail sheet, even though they're granted at account creation, not earned — a fresh account's seeded `acquiredAtEpochMs` resolves to "today".
**Approach:** Extracted the "which acquisition line, if any" decision out of the Composable into a pure `acquisitionLineKind(item)` (in `items/AcquisitionLine.kt`) so it has a real regression guard, returning null for `isDefaultCosmetic(...)` ids (and rows with no acquisition timestamp). `CosmeticDetailSheet.acquisitionLine` renders off the pure kind. Test-first: `AcquisitionLineTest` asserts default felt/card back → null, earned → Earned, bought → Bought(cost), free grant → BoughtFree.
**Reviewer notes:** The "earned badge" the todo named is really this detail-sheet line — there's no separate corner "earned" badge on the tile (corner badges are equipped/locked only). Pairs with the backlog "Earn-source attribution on My Items 'Earned' rows."
