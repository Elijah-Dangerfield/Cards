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
