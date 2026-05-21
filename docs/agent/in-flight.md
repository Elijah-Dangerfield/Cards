## fix(nav): cover-and-uncover transitions; previous screen stays put

**Problem:** Forward push and back pop both animated the same screen pair in the same visual direction. A screen pushed with `SlideUp` slid up into view, but on pop the *same* screen also slid up (off the top) instead of sliding back down to reveal the previous screen underneath. Same root cause across the wiring (`App.kt`) and the animation mapping (`Route.kt`).

**Approach:** Two-part fix.
- `apps/compose/.../App.kt`: collapse `exitTransition` and `popEnterTransition` to unconditional `ExitTransition.None` / `EnterTransition.None`. Now only the entering / popping screen moves; the previous screen sits put. This is the "cover and uncover" pattern the todo described, matches platform sheet semantics, and `isSwitchingTabs()` is no longer needed in those two branches (tab-swap nav already produces None unconditionally).
- `libraries/navigation/.../Route.kt`: `AnimationType.SlideUp` and `SlideDown` were mapped to the *opposite* `slideOutVertically` offsets in `toExitTransition()` — `SlideUp` exit visually moved down, `SlideDown` exit visually moved up. Swapped to match the names. Without this, `SignInRoute.popExit = SlideDown` (and the equivalent on `ShakeDialogRoute` / `ErrorDialogRoute`) would still slide UP off the top after the wiring change.

Chose this over the todo's literal "popExit = `opposite(enter)`" structural change because the current `opposite()` is a *mirror* (`SlideInFromRight ↔ SlideOutToLeft`) not a *reversal* (`SlideInFromRight ↔ SlideOutToRight`) — so wiring it as the default would regress horizontal back-out for `SignUpRoute` / `VerifyEmailRoute` / every `Route()` default. Captured the structural follow-up in `docs/backlog.md` so it can be picked up alongside an `opposite()`-vs-`reversal()` rename.

**Reviewer notes:** Visual change — needs eyeball-verification on device. Walk through: (1) push `SignInRoute` from the intro pager → SignIn slides up over the intro, intro stays put. Pop back → SignIn slides DOWN out the bottom, intro stays put. (2) Push any `Route()`-default child (e.g. Profile → EditProfile) → child slides in from right, parent stays put. Pop → child slides out to right, parent stays put. (3) Tab switching (Home ↔ Shop ↔ Profile) → no animation, unchanged. The `Route.exit` field is now unused by `NavHost`; left in place rather than ripping it out across every subclass — separable cleanup if/when the structural follow-up lands.

**Deferred:**
- Default `Route.popExit` derived from `enter.reversal()` so new routes can omit the override — needs the `opposite()` → `reversal()` rename first. Captured in `docs/backlog.md`.
- `Route.exit` field is now dead from `NavHost`'s perspective. Could be removed across all subclasses in a separable refactor — not now, since it's still descriptive and removing it would touch ~7 route files.

## refactor(ds): swap non-game-table `RoundedCornerShape` literals for `Radii` tokens

**Problem:** Backlog item "Sweep remaining `RoundedCornerShape(16.dp)` literals → `Radii.R700.shape`" listed eleven hand-tuned corner-radius literals. AGENTS.md DS rule §5 says corner radii come from `Radii` tokens; one-off `dp` literals at these sites had drifted past what the design system codifies.

**Approach:** Migrated the non-game-table callsites the backlog flagged as mechanical:
- `MyItemsScreen.kt:117` — 16.dp → `Radii.R700.shape` (also removed now-unused `RoundedCornerShape` import)
- `StatsScreen.kt:214/237/400` — three 16.dp callsites → `Radii.R700.shape` (import kept; file still uses `RoundedCornerShape(50)` for chip rows)
- `FeatureCard.kt:59` — 16.dp → `Radii.R700.shape` (import kept for the 24.dp outer shape, which has no token yet)
- `QaMenuScreen.kt` — five callsites: three `RoundedCornerShape(10.dp)` → `Radii.R400.shape` (UserIdBlock, "Clear all overrides", text-field wrapper, Apply button) and one `RoundedCornerShape(16.dp)` → `Radii.R700.shape` (QaSection card). Import kept for the remaining `RoundedCornerShape(50)` chip.

Skipped the game-table callsites (`BoardArea.kt`, `HandResultDialogs.kt:272`, `PlayerArea.kt:240/245`) per the backlog note — those were tuned by hand for the play screen and need a deliberate visual sweep, not blind replace. Updated `docs/backlog.md` to reflect what's left.

**Reviewer notes:** Pure DS-token swap — `Radii.R400` is 10.dp and `R700` is 16.dp via `DimensionResource`, so the rendered shape is unchanged. Built `./gradlew :apps:compose:assembleDebug` and ran `:features:profile:impl:testDebugUnitTest`, `:features:progression:impl:testDebugUnitTest`, `:libraries:ui:testDebugUnitTest` — all green. No tests added; mechanical refactor with no logic surface.

**Deferred:**
- `FeatureCard.kt:46` still uses `RoundedCornerShape(24.dp)` (no token) and `Color.White.copy(alpha = 0.15f)` on the glyph block — both are AGENTS.md DS-rule drift but out of scope for this radii sweep. Left as-is; reviewer please triage whether to file as a separate backlog item (new `Radii.R800`/`R900` token + white-alpha-on-surface remediation) or roll into the next DS pass.
- `StatsScreen.kt` and `QaMenuScreen.kt` still use percent-based `RoundedCornerShape(50)` for chips — `Radii.Round` exists (`CornerSize(percent = 50)`) and would cover them, but those weren't in the backlog item so I left them. Mechanical follow-up if/when we tighten DS-token coverage.
