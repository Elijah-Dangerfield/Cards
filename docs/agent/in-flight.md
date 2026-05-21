## refactor(ds): add Radii R800/R900/R1000 + sweep non-table 20/24dp callsites

**Problem:** Previous worker's deferred note flagged `FeatureCard.kt:46` still on `RoundedCornerShape(24.dp)` because no `Radii` token covered 24.dp. Same gap blocked clean migration of `AchievementMedallion.kt:81` (24.dp) and `LobbyScreen.kt:178` (20.dp). The scale jumped from R700 (16.dp) straight to the percent-based `Round` — anything in the 20–28.dp band stayed as a literal, exactly the DS-rule drift AGENTS.md §5 warns about.

**Approach:** Extended the numeric `Radii` scale with three new tokens — `R800` (D800 = 20.dp), `R900` (D900 = 24.dp), `R1000` (D1000 = 28.dp). They follow the existing pattern (`Radius(CornerSize(DimensionResource.DXXX.dp))`) so they pick up the `DimensionResource` source of truth. Then swapped the three non-game-table callsites: `FeatureCard.kt` (24 → R900), `AchievementMedallion.kt` (24 → R900), `LobbyScreen.kt` (20 → R800). Removed now-unused `RoundedCornerShape` import from `FeatureCard.kt` and `LobbyScreen.kt`; kept it in `AchievementMedallion.kt`? — checked, only one usage, dropped that import too. Added the `Radii` import to `AchievementMedallion.kt`.

Game-table callsites (`HandRankingsCheatSheet`, `PlayerArea`, `RaiseSheet`, `TableActionBar`, `BoardArea`, `HandResultDialogs:272`) deliberately left as literals — same caveat the previous worker honored for the R700 sweep: "tuned by hand for the play screen, deliberate visual sweep needed, not blind replace." Updated `docs/backlog.md` to fold the new tokens into the existing backlog entry and list every game-table site with its target token, so the next pass is mechanical.

**Reviewer notes:** Pure DS-token additions + swaps — `R800/R900/R1000` resolve to 20/24/28 dp via `DimensionResource`, so every migrated callsite renders identically. Built `./gradlew :apps:compose:assembleDebug` (green) and ran `:libraries:ui:testDebugUnitTest :features:progression:impl:testDebugUnitTest :features:lobby:impl:testDebugUnitTest` (all green). No tests added — extending a token scale + mechanical swap with no logic surface.

**Deferred:**
- `FeatureCard.kt:61` still has `Color.White.copy(alpha = 0.15f)` on the glyph block — the white-alpha-on-accent-gradient pattern AGENTS.md DS rule §1 calls out. Picking the right replacement is a visual design call (does the DS need a new "overlay on accent" token? Or should this resolve to `surfacePrimary.copy(alpha = ...)`?), so I left it for the reviewer to triage with the human rather than burning the surface-token decision in a follow-up commit.
- Game-table corner-literal sweep — captured in `docs/backlog.md` with every callsite's target token. Reviewer please leave as backlog; should land alongside the next deliberate play-screen visual pass, not this DS-token housekeeping.

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

## refactor(ds): route emoji bubbles through composable factories

**Problem:** `docs/todo.md` §B "Design system — dialog & sheet primitives" Step 3 — `DialogEmoji` (data class) and `BottomSheetDragHandle.Emoji` (sealed-interface variant) had public constructors, so callers were bypassing the composable factories that exist (`dialogEmoji(...)` for dialogs; nothing for sheets). That left the DS unable to advertise a theme-aware default surface from one place — exactly the chokepoint the todo wanted before the surface-token pin (a separate visual design call) can happen.

**Approach:** Two parallel changes, mechanical.
- `DialogEmoji` constructor → `internal`. Added a docstring noting `:libraries:ui`-only construction. Migrated the 7 external callsites (`LeaveBotsConfirmDialog`, `BotTableSetupDialog`, `LastActionExplainer`, `HandResultDialogs` ×2, `BlindRolesExplainer`, `UserMessageOverlay`, `ProfileScreen`) from `DialogEmoji(emoji = "...")` to `dialogEmoji(emoji = "...", surface = null)`. The explicit `surface = null` is load-bearing — without it, the factory's `BubbleSurface.Solid(AppTheme.colors.surfaceTertiary)` default fires, which is a *visual change* the todo flagged as a human design call. Passing `surface = null` preserves the prior rendering exactly (the `Dialog` composable falls back to `surfacePrimary` when emoji.surface is null). Stale `DialogEmoji` import removed from `HandLabelExplainer` (already on the factory).
- `BottomSheetDragHandle.Emoji` constructor → `internal`. Added a sibling factory `bottomSheetEmojiHandle(emoji, style, surface)` in `BottomSheetDragHandle.kt` whose shape mirrors `dialogEmoji(...)` exactly (same theme-aware `surfaceTertiary` default). Migrated `PurchaseConfirmSheet.kt:88`, the only external caller — it already passes an explicit `surface` so the factory default doesn't apply, no visual change. Internal previews (`BottomSheet.kt`, `BaseBottomSheet.kt`) keep direct `BottomSheetDragHandle.Emoji(...)` construction since they live in the same module as the `internal constructor`.

Chose explicit `surface = null` over (a) letting the factory default apply and (b) changing the factory default itself. Option (a) is the visual design call the todo note pinned to the human. Option (b) breaks the parallel between `dialogEmoji` and `bottomSheetEmojiHandle` defaults — the whole point of the chokepoint is one knob to turn. With the chokepoint now in place, the human can flip the surface-token pin in one place and drop the explicit `surface = null` overrides in one follow-up sweep. Refiled the todo entry around that follow-up so the surface-token pin is the next pickup, not the constructor migration.

**Reviewer notes:** Visual-safety claim: every migrated callsite renders identically to before because `surface = null` resolves to the same `surfacePrimary` fallback the raw data class constructor used. Eyeball-verify if you want: pop any dialog with an emoji (e.g. leave confirm, achievement showdown, sign-out) and any product purchase sheet — the bubble fill should be unchanged. Built `./gradlew :apps:compose:assembleDebug` green; `:libraries:ui:testDebugUnitTest :features:room:impl:testDebugUnitTest :features:home:impl:testDebugUnitTest :features:shop:impl:testDebugUnitTest :features:profile:impl:testDebugUnitTest` all green. No tests added — pure visibility/chokepoint refactor with no logic surface and no testable observable behavior.

**Deferred:**
- Surface-token pin itself — refiled in `docs/todo.md` §B as the next pickup. Human design call: pick the canonical bubble surface, then drop the 7 `surface = null` overrides from the migrated dialog callsites. Reviewer please triage as worker-pickable only after the human resolves the pin.
