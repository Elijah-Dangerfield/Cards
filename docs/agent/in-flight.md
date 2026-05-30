# In-flight

Per-commit handoff notes for the reviewer. One block per commit this cycle.

## fix(progression): scroll multi-achievement celebration sheet

**Problem:** When several achievements unlock at once, the celebration sheet stacked the cards without scrolling, so they overflowed / clipped past the sheet's max height.
**Approach:** Switched `AchievementCelebrationSheet` from the all-in-one composite-title `BottomSheet` overload to the base overload — title is now `stickyTopContent`, the cards live in a `verticalScroll` `content`, and the continue button is pinned as `stickyBottomContent`. This leans on `ModalContent`'s existing weighted-middle layout (the same scroll pattern `PreviewModalContentLong` demonstrates) instead of adding a height param to the DS.
**Reviewer notes:** Pure layout change, no unit test (covered by the existing stacked-multiple preview). Note the todo hint said the sheet lives in `:features:progression:impl`; it's actually in `:features:room:impl` — fixed the work, the hint was just wrong.

## feat(progression): loss-disclosure on Stats page for anonymous users

**Problem:** Anonymous users past level 1 had no nudge on the Stats page to claim their account, so they could lose progress with no warning.
**Approach:** `StatsViewModel` now observes `AuthRepository.observe()` on its own collector (kept separate from the 3-flow data `combine` so auth resolution can't stall the stats render) and exposes `isAnonymous`. `StatsScreen` renders a compact `ClaimDisclosureCard` (DS `Surface`, surfacePrimary, accentPrimary CTA) under the XP hero only when `isAnonymous && level > 1`; the entry point routes its tap to `ClaimAccountRoute`. Added `features:profile` as a dep on `:features:progression:impl` for the route.
**Reviewer notes:** Direction call — I placed the disclosure right under the XP hero (where the user just saw the level/XP it protects) rather than at the bottom; easy to move. Copy lives in new `stats_claim_disclosure_*` strings. VM tests cover anonymous→true / claimed→false; the card itself has a preview.
