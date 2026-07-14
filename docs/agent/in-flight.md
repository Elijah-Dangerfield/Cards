# In-flight

## refactor(ui): move HandRankingsCheatSheet onto opinionated BottomSheet (GAME-32)

**Problem:** `HandRankingsCheatSheet` escaped to `BaseBottomSheet` via `@OptIn(LowLevelDSComponent::class)` because the opinionated `BottomSheet` had no clean way to host a tall, caller-owned scrollable body with no title framing.
**Approach:** Added an opt-in `scrollableContent: Boolean = false` to the opinionated `BottomSheet` — when true it wraps the `content` slot in a `Column(verticalScroll)` so sheets stop hand-rolling scroll. Migrated the cheat sheet onto it (dragHandle None, surface bg, `scrollableContent = true`), dropping the hand-rolled scroll column and its one-off 24dp padding (DS gutters now own it). Chose a small opt-in flag over a second `ScrollableBottomSheet` component (would duplicate the shell) and over just reusing the existing content slot with a hand-rolled scroll (leaves the boilerplate tribal and doesn't satisfy the item's "add the content shape to BottomSheet").
**Reviewer notes:** No unit tests — this is presentational DS plumbing; coverage is the three existing `@Preview`s on the cheat sheet plus the sheet previews. Verified via `:libraries:ui` + `:features:room:impl` metadata compile and client assembleDebug. The sibling `HowToPlaySheet` still hand-rolls its own `Column(verticalScroll)` inside a title sheet — left untouched this cycle (see Deferred).
**Deferred:** `HowToPlaySheet` could adopt `scrollableContent` too, but it rides the `title`-overload (which doesn't thread the flag) — threading `scrollableContent` through the two title overloads is a clean follow-up. Nothing filed yet — reviewer please triage.

## docs(wiki): fix stale symbol references (ENG-34)

**Problem:** Two "Key files" pointers in the wiki named symbols that no longer exist, sending a reader to a dead reference.
**Approach:** Repointed `client-patterns.md` at `ProductsRepositoryImpl` (`:libraries:products:impl`) instead of the removed `ShopCatalogRepositoryImpl`, and `multiplayer.md` at `MatchmakingRoutes.kt` instead of the removed `PublicMatchmakingRoutes.kt`. Verified both old symbols are gone from the tree and both new ones exist; grepped `docs/` to confirm no other stragglers.
**Reviewer notes:** None. Doc-only.
