# In-flight log — current cycle

Workers append blocks per commit. Reviewer reads when writing the PR and deletes the file before the squash-merge.

## docs(todo): drop shipped Sentry wiring item

**Problem:** `docs/todo.md` §C Observability still tracks "Wire Sentry — single project, platform-tagged" as a `[P1]` action item, but Sentry is fully wired on both surfaces.
**Approach:** Removed the bullet. Verified the wiring on both surfaces: client init at [AppTelemetry.kt:73](../../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AppTelemetry.kt) (`Sentry.init` via `io.sentry.kotlin.multiplatform`, options.release + platform/build_type tags via `setExtra`, SentryLogTree planted), server init at [Sentry.kt:39](../../apps/server/src/main/kotlin/com/cards/server/plugins/Sentry.kt) (Sentry JVM, options.environment + release set, uncaught-exception handler enabled), versions catalog declares `sentryKmp = "0.26.0"` at [libs.versions.toml](../../gradle/libs.versions.toml). Implementation went per-platform DSN rather than the bullet's "single project" recommendation, but the acceptance criteria (platform-tagged exceptions on each surface) are met by the current wiring. The 2026-05-29 hydrator cycle's reconcile commit had landed the same removal; reset wiped it, so this re-lands it.
**Reviewer notes:** None — pure docs delete.

## refactor(ui): relocate FeatureCardAccents to system/color

**Problem:** AGENTS.md flags raw `Color(0xFF…)` literals outside `:libraries:ui/system/color/` as a DS-drift anti-pattern. `FeatureCardAccents` (`Green`/`Blue`/`Magenta`/`Gold` — used by Home's CTA tiles and the tutorial banner) was a top-level `object` declared inside [`FeatureCard.kt`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/FeatureCard.kt), in `:libraries:ui/components/`.
**Approach:** Moved the object into its own file at [`FeatureCardAccents.kt`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/system/color/FeatureCardAccents.kt) under `:libraries:ui/system/color/`, alongside [`PokerPalette`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/system/color/PokerPalette.kt). Hex literals unchanged; updated the three callers' imports (`HomeCtaCard.kt`, `HomeScreen.kt`, `TutorialBanner.kt`) and dropped the old declaration from `FeatureCard.kt`. Chose "new file" over "add to PokerPalette" because the accents are not poker-game-specific and PokerPalette's docstring scopes it to "physical poker objects" — wrong neighborhood. Source-only refactor; no rendered pixels change.
**Reviewer notes:** None.
**Source:** worker-hydrated this cycle (not human-curated). Lane D — pattern flagged by a prior hydrator run wiped during the start-of-cycle reset; re-derived from the same AGENTS.md rule.

## refactor(ui): move achievement rarity raw colors into system/color palette

**Problem:** Same AGENTS.md rule. [`AchievementRarityColors.kt`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/achievement/AchievementRarityColors.kt) (the `AchievementRarity.toAccentColor()` extension) hard-coded three raw `Color(0xFF…)` literals inside `:libraries:ui/components/achievement/`, with EPIC already routing through `PokerPalette.ChipGold`.
**Approach:** Added [`AchievementRarityPalette`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/system/color/AchievementRarityPalette.kt) under `:libraries:ui/system/color/` with `Common`/`Rare`/`Epic`/`Legendary` entries (Epic aliases `PokerPalette.ChipGold` so the chip-gold + Epic-glow single-source-of-truth is preserved). Rewrote `toAccentColor()` to map through the palette — same hex values, one indirection layer that satisfies the "raw literals live under system/color/" rule. Chose a sibling palette object (rather than appending to `PokerPalette`) because rarity colors aren't a "physical poker object" — keeping the palettes scoped by domain matches PokerPalette's docstring intent. Extension function stays in `components/achievement/` so the `AchievementRarity` ↔ `Color` adapter sits next to the components that consume it.
**Reviewer notes:** None.
**Source:** worker-hydrated this cycle (not human-curated). Same lane / same source as the prior block.

