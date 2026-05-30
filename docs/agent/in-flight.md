# In-flight

Per-commit handoff notes for the reviewer. One block per commit this cycle.

## fix(progression): scroll multi-achievement celebration sheet

**Problem:** When several achievements unlock at once, the celebration sheet stacked the cards without scrolling, so they overflowed / clipped past the sheet's max height.
**Approach:** Switched `AchievementCelebrationSheet` from the all-in-one composite-title `BottomSheet` overload to the base overload — title is now `stickyTopContent`, the cards live in a `verticalScroll` `content`, and the continue button is pinned as `stickyBottomContent`. This leans on `ModalContent`'s existing weighted-middle layout (the same scroll pattern `PreviewModalContentLong` demonstrates) instead of adding a height param to the DS.
**Reviewer notes:** Pure layout change, no unit test (covered by the existing stacked-multiple preview). Note the todo hint said the sheet lives in `:features:progression:impl`; it's actually in `:features:room:impl` — fixed the work, the hint was just wrong.
