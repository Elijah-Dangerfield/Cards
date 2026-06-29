# AGENTS.md

Guidelines for AI agents working in the Cards repository.

## Overview

KMP (Kotlin Multiplatform) app with Compose Multiplatform. Modular architecture with Room database, navigation, and SEAViewModel pattern. Most code is shared; platform features (permissions, sensors, native APIs) need platform-specific impls — follow `docs/practices/swift-kotlin.md`.

## Where work comes from

| Surface | Shelf life | Who picks from it | Contents |
| --- | --- | --- | --- |
| [`docs/todo.md`](docs/todo.md) | Standing | AI workers + human | Active engineering work — current ship target. Everything here is worker-pickable. |
| [`docs/backlog.md`](docs/backlog.md) | Standing | Human curates; AI may append | Someday/maybe + follow-ups. Workers don't pick from here; they **append** when noticing good follow-ups outside current scope. |
| [`docs/developer-todo.md`](docs/developer-todo.md) | Standing | Human curates; reviewer may append | Human-only — credentials, GitHub settings, dashboard config, device QA, content writing, deferred product decisions. **Workers must never edit.** Reviewer may *append* a one-line entry per cycle; may not edit/delete existing entries. |
| [`docs/QA.md`](docs/QA.md) | Standing | Human runs the checklist on a real device pre-release; workers may append | Pre-launch verification organised by feature. Each test has a stable ID + State / Steps / Expected. |
| PR "Heads up" section | Ephemeral (per PR) | Reviewer writes; human reads | Per-cycle follow-ups tied to *this* PR's diff. Lives in the PR body. |

Routing for human-only items: dies the moment the human acts on it this cycle → PR Heads up; standing across cycles → `developer-todo.md` (mention once in Heads up so it's not invisible).

Closing a `docs/todo.md` item that involved a non-trivial architectural call → add an entry to [`docs/decisions.md`](docs/decisions.md).

Shipping a user-facing change → decide whether [`docs/QA.md`](docs/QA.md) needs updating: new feature → add a new test; UX tweak → sub-bullet on existing coverage; backend / invisible → skip. Match the file's existing format (ID + priority emoji + platform tag + **State** / steps / **Expected**).

Nightly automation uses [`docs/agent/`](docs/agent/) for prompts and the ephemeral `in-flight.md` handoff log (workers create, reviewer deletes in the PR).

## Build Commands

```shell
./gradlew :apps:compose:assembleDebug          # Android
./gradlew :apps:compose:compileKotlinIosSimulatorArm64  # iOS Kotlin
xcodebuild -project apps/ios/iosApp.xcodeproj -scheme iOS -sdk iphonesimulator  # iOS full
```

## Module Structure

```
apps/compose/          # KMP entry point (Android + iOS)
apps/ios/              # Swift wrapper
features/<name>/       # Routes, public API
features/<name>/impl/  # Screens, ViewModels
libraries/<name>/      # Interfaces
libraries/<name>/impl/ # Implementations
```

**Rules** — enforced by the convention plugins at Gradle configuration:

- Only `:apps:*` may depend on `*:impl`. Impls are DI wiring composed by the app.
- Feature `impl` may depend on another feature's `api`. Feature `api` may **not** depend on other feature `api`s (cycle risk — shared types go in a library).
- Sub-modules of the same feature (`:features:foo:storage` → `:features:foo`) are allowed.
- `:libraries:storage:impl` is the one shared impl — it owns `AppDatabase`.

Shared code → libraries. Main modules expose interfaces only; impl modules contain implementations.

## Conventional Commits (required)

Every commit and PR title (PRs squash-merge) must follow [Conventional Commits](https://www.conventionalcommits.org/). Release-please derives the next version bump from commit history.

| Type | When | Version bump |
| --- | --- | --- |
| `feat:` | User-visible new capability | minor |
| `fix:` | Bug fix | patch |
| `perf:` | Perf improvement, user-visible | patch |
| `feat!:` / `BREAKING CHANGE:` | Breaking change | major |
| `refactor:`, `style:`, `test:`, `docs:`, `ci:`, `build:`, `chore:`, `revert:` | No user impact | none |

`.githooks/commit-msg` enforces this locally. Gradle fails with an install-hooks message if the hook isn't wired — run `./scripts/install_hooks.sh`.

## Convention Plugins

| Plugin | Use |
|--------|-----|
| `cards.kotlin.multiplatform` | Pure Kotlin |
| `cards.compose.multiplatform` | Kotlin + Compose |
| `cards.feature` | Feature modules |
| `cards.application` | apps:compose only |

Use `/scripts/create_module` for new modules.

## DI (kotlin-inject-anvil)

```kotlin
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class MyImpl : MyInterface

// Multibinding for FeatureEntryPoints
@ContributesBinding(AppScope::class, multibinding = true)
```

No expect/actual for platform impls — bind different implementations per platform. iOS impls written in Swift get passed into the DI graph via `IosAppComponentFactory.create(...)`.

## SEAViewModel Pattern

```kotlin
class MyViewModel : SEAViewModel<State, Event, Action>(initialStateArg = State()) {
    override suspend fun handleAction(action: Action) {
        when (action) {
            is Action.Load -> action.updateState { it.copy(loading = true) }
        }
    }
}
```

- **State**: Immutable data class for UI
- **Event**: One-shot side effects (navigation, toasts)
- **Action**: Only way to mutate state via `action.updateState { }`

### Fire-and-forget actions outlive the screen

`handleAction` runs on `viewModelScope`. If the user navigates away mid-action, the VM dies and in-flight work cancels. For actions whose *completion must reach the server* even if the user pops the screen (leaving a room, forfeiting a seat, telemetry writes), inject `AppCoroutineScope` from `:libraries:flowroutines` and wrap the network call:

```kotlin
@Inject
class MyViewModel(
    private val repo: MyRepository,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<State, Event, Action>(initialStateArg = State()) {
    override suspend fun handleAction(action: Action) {
        when (action) {
            is Action.LeaveAndDontCareIfWeArrive -> {
                val outcome = appScope.async { repo.leave() }.await()
                // continuation only runs if the VM is still alive
            }
        }
    }
}
```

`appScope.async { … }.await()` keeps the call's job parented to the app-lifetime scope; `viewModelScope`'s cancellation only cancels the awaiting continuation, not the underlying call. Anything that only exists to update the screen's own state stays in `viewModelScope`.

## Coroutines & dispatchers

**Never reach for `Dispatchers.{Main,IO,Default,Unconfined}` directly in production code.** Inject [`DispatcherProvider`](libraries/flowroutines/src/commonMain/kotlin/com/cards/libraries/flowroutines/DispatcherProvider.kt) and use `dispatchers.io`, `dispatchers.default`, etc. The DI graph already binds `DefaultDispatcherProvider`.

```kotlin
@Inject
class MyRepository(
    private val dispatchers: DispatcherProvider,
) {
    suspend fun heavyWork() = withContext(dispatchers.default) { … }
}
```

Why: tests swap in `TestDispatcherProvider` (from `:libraries:flowroutines:testing`) whose four dispatchers all route to one `TestDispatcher`, so the test scheduler controls every coroutine — including CPU-bound work. Direct `Dispatchers.Default` can't be virtualized and produces flaky tests racing a real thread pool.

Same rule for `withContext`, `launch(context = …)`, and any constructor parameter taking a `CoroutineDispatcher`: take the provider, not the dispatcher.

## Testing infrastructure

**Extend `CoroutineTest`** ([libraries/flowroutines/testing](libraries/flowroutines/testing/src/commonMain/kotlin/com/cards/libraries/flowroutines/testing/CoroutineTest.kt)) for any test touching a ViewModel, `Flow`, or suspend code. Add to `commonTest` deps:

```kotlin
commonTest.dependencies {
    implementation(projects.libraries.flowroutines.testing)
}
```

What you get:

- `Dispatchers.setMain` / `resetMain` installed around each test — `viewModelScope` routes through the test scheduler.
- `dispatchers: DispatcherProvider` — pass to any production class that takes one. All four dispatchers are the same `TestDispatcher`.
- `runUnitTest { … }` — wraps `runTest` and cancels leaked child coroutines on exit, so long-lived workers (bot loops, infinite-flow collectors) don't trip `UncompletedCoroutinesError`.

```kotlin
class MyVmTest : CoroutineTest() {
    @Test
    fun loadsData() = runUnitTest {
        val vm = MyViewModel(repo, dispatchers)
        repo.emit(Foo(...))
        assertEquals(Foo(...), vm.state.foo)
    }
}
```

Default dispatcher is `UnconfinedTestDispatcher` — continuations run eagerly so `vm.takeAction(...)` propagates to `vm.state` in the same virtual tick. Override `testDispatcher` in a subclass when you need explicit `runCurrent` / `advanceUntilIdle` control.

**Use [Turbine](https://github.com/cashapp/turbine) for Flow assertions.** `:libraries:flowroutines:testing` re-exports it.

```kotlin
@Test
fun emitsExpected() = runUnitTest {
    sut.flow.test {
        assertEquals(Initial, awaitItem())
        sut.trigger()
        assertEquals(Updated, awaitItem())
    }
}
```

Don't write `Dispatchers.setMain` boilerplate per test file — that's what the base is for. If a test needs something `CoroutineTest` doesn't expose, lift it into `CoroutineTest` rather than re-inventing per file.

## Navigation

Routes are `@Serializable` data classes extending `Route`. Register in `FeatureEntryPoint.buildNavGraph()`:

```kotlin
screen<MyRoute> { backStackEntry -> MyScreen(...) }
bottomSheet<SheetRoute> { backStackEntry, sheetState -> ... }
dialog<DialogRoute> { backStackEntry, dialogState -> ... }
navigation<MyGraph>(startDestination = MyRoute()) { screen<...>; bottomSheet<...> }
```

**Use `bottomSheet<>` for transient picker / overlay UIs** (settings list, "select an item" sheet) rather than pushing a full screen. Backstack stays one deep, the underlying screen shows under a scrim, `sheetState.dismiss()` is a clean exit. Reach for `screen<>` only when the destination is its own context.

**Open external URLs via `Router.openWebLink(url)`** — don't roll your own `Intent.ACTION_VIEW` / `UIApplication.shared.open`. Impl lives in `libraries/navigation/impl/.../{Android,Ios,Jvm}WebLinkLauncher.kt`, already DI-wired.

### Routing rules

**Composables don't route.** Navigation is initiated from a ViewModel or a feature entry point (the `buildNavGraph` lambdas), not from a leaf view. A button needing to navigate fires a callback/action; the entry point or VM translates it to a `Router` call. Keeps the graph greppable from one place and screens trivially previewable.

**`Router` is the only navigation API.** No `LocalNavController`, no other `NavHostController` handle. If you want one, the operation you need probably belongs on `Router` — promote it. Sole escape hatch for **VM scoping** is `Router.backStackEntryFor<T>()`, wrapped by `Router.graphScopedViewModel<TGraph, TVm>(factory)`. Don't use it for anything else.

**Tab roots are arg-less.** `HomeRoute`, `ShopRoute`, `ProfileRoute` and future tab destinations take no constructor args. Tab switching uses `saveState` + `restoreState`, which restores the saved entry's original args verbatim — new args on a fresh `switchTab(SameRoute(newArgs))` are silently dropped. Put one-shot intent on a **sub-route** of the tab. See `docs/decisions.md` (2026-05-24).

**Cross-tab deep-links chain through `Router.batch`.** Switch tabs and push a sub-route atomically:

```kotlin
router.batch {
    switchTab(ShopRoute())
    navigate(ShopProductSheetRoute(productId))
}
```

Don't write the two calls sequentially — if the caller's scope tears down between them, the second never runs. `batch` queues the whole block as one op.

**Share a ViewModel across screens in the same tab by nesting in a graph.** Wrap the tab's routes in `navigation<TabGraph>(startDestination = TabRoot()) { ... }` and resolve via `router.graphScopedViewModel<TabGraph, TabViewModel> { factory() }` from each screen. The VM lives as long as anything in the graph is on the back stack — natural scope for tab-wide state.

## App-wide state

`AppData` (in `libraries/<projectid>/.../AppCache.kt`) is a `@Serializable` data class persisted via `CacheFactory.persistent`. Add fields here for:

- Onboarding flags (`hasUserOnboarded`)
- User-facing setting toggles
- Counters / lightweight telemetry (`feedbacksGiven`, `bugsReported`)

Don't roll a new persistent cache for a single boolean — extend `AppData`. Round-trip is automatic via `versionedJsonSerializer` (missing fields fall back to defaults). For a `StateFlow<Boolean>` wrapper example, see how a feature-level store reads `AppCache.updates` and writes via `appCache.update { it.copy(...) }`.

## Repository read-path caching

For server-driven reference data — catalogs, packs, anything where a slightly stale view is fine — repositories follow the **session-aware cache pattern**: persist the last successful response to disk, hydrate on init so the first frame has content, and only re-fetch when [`SessionTracker`](libraries/cards/src/commonMain/kotlin/com/cards/libraries/Session.kt) signals a new session (cold boot or foreground after ≥15 min in background). Pull-to-refresh / forced refresh always bypasses. A persisted snapshot older than the per-repo max age (7 days for both adopters today) gets dropped on read so the user never sees something hopelessly stale.

The two reference implementations:

- [`ProductsRepositoryImpl`](libraries/products/impl/src/commonMain/kotlin/com/cards/libraries/products/impl/ProductsRepositoryImpl.kt) — shop catalog. Observable shape (`observeCatalog`, `observeIsRefreshing`); the repo auto-refreshes on session rollover, the VM just subscribes.
- [`ProfileRepositoryImpl.fetchAvatarPack`](libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/profile/ProfileRepositoryImpl.kt) — avatar pack catalog. Suspend shape with internal dedup; same disk + session machinery underneath, plus a hardcoded fallback pack so a network failure on a fresh install still renders a usable picker.

Lean on the cache by default. Showing yesterday's data while a refresh lands is almost always better than showing a loading spinner over an empty surface. New surfaces that read reference data: pick one of these two impls as a template. See [`docs/decisions.md` — "2026-05-25 — Session-aware repository refresh"](docs/decisions.md).

### When this pattern fits — and when it doesn't

Pick the read-path policy **per repository**, not globally. Consistency vs. availability is a real trade-off and different resources sit in different places on it.

| Resource shape | Pattern |
|---|---|
| **Server-driven reference data** that changes on the order of days (catalogs, packs, app config, leaderboard tier definitions) | **Session-aware cache** — disk-persisted, refresh on session rollover, pull-to-refresh forces. Shop catalog + avatar pack are the references. |
| **Per-user mutable state** with optimistic local writes (inventory after a purchase, equipped cosmetics, chip wallet, XP ledger) | **Write-through + sync** — local DB (Room) is the source of truth between syncs; server reconciles. *Not* session-aware: forcing this pattern would make a just-redeemed item "vanish" until the next session boundary. See `InventoryRepositoryImpl` / `EquipmentRepositoryImpl` / `ChipsRepositoryImpl`. |
| **User-mutable identity / profile** (display name, avatar emoji on `/v1/me`) | **Auth-driven cache** — refresh when auth state changes; writes apply optimistically. *Not* session-aware: a user who just renamed themselves shouldn't see the old name for the rest of the session. See `ProfileRepositoryImpl`'s profile resolve path. |
| **Time-sensitive per-user inbox** (announcements, urgent banners) | **Refresh-on-entry** with a short freshness window. A missed inbox item is worse than a wasted fetch. |
| **Live state** (open WebSocket rooms, online presence, current hand) | **Never cache** — subscribe to the live source. |
| **Money / accuracy-critical reads** (final wallet balance for a purchase confirmation, hand-history audit) | **Always-network**. A stale chip balance shown next to a "Buy" button is a real bug. |

Bias toward cache-first when in doubt, but never force this pattern onto a resource whose contract demands freshness. When extending a repo, write down which row above it falls in — the read-path-policy bullet in `docs/developer-todo.md` tracks the remaining unconverted reads.

**Write-path / grants** are the counterpart: where a *mutation* (earning XP, granting a prize, spending chips) lives and who's authoritative. Cards is offline-first, so default to **grant locally + reconcile on sync** (idempotent) for valuable state, keep purely-local state client-side, and reserve **server-authoritative grants** (not offline-friendly) for values the client can't compute or that need real trust. The full model + how-to-choose is in [`docs/wiki/state-authority-and-sync.md`](docs/wiki/state-authority-and-sync.md).

### Boot-time construction: the `AutoInit` marker

The session-aware cache only works if the repository is constructed before the user touches the screen that reads it. Kotlin-inject singletons are constructed lazily on first injection, so a repo that nobody touches until a deep nav target stays cold — the hydrate-from-disk and session-rollover observer don't run, defeating the point.

For repositories where the warm path matters (catalog grids, avatar pickers, app-lifecycle dispatchers, anything whose `init {}` is load-bearing), implement [`AutoInit`](libraries/core/src/commonMain/kotlin/com/cards/libraries/core/AutoInit.kt) and contribute a second binding via multibinding:

```kotlin
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProductsRepository::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class ProductsRepositoryImpl(...) : ProductsRepository, AutoInit
```

The `Set<AutoInit>` is resolved at app start (`CardsApplication.onCreate` on Android, `iOSApp.init` on iOS, `App.kt` remember-block on Compose first composition). Resolving the set forces every contributor to construct, which runs their `init {}` — that's where hydrate-from-disk + session-observer subscription + lifecycle-listener registration happen.

**Opt in** when first-touch latency the user notices, an `init {}` that registers a listener, or a session-aware cache that needs its observer running before the user can navigate. **Skip** for debug-only / config-override / QA-menu repos and anything whose `init {}` is empty. The failure mode for forgetting the marker is a perf regression, not a correctness one — the class still works lazily — so the bigger risk is overuse making boot slow.

## Compose previews (required for screens)

Every user-facing screen composable needs `@Preview` coverage. Without it, iterating means rebuild → reinstall → navigate → set up state by hand, every change. With previews, Studio renders the screen instantly across states.

**The rules:**

1. **Every public screen-level composable** in a `:features:*:impl` module must have at least one `@Preview`. New screens land with previews in the same PR.
2. **Cover the meaningful states**, not just happy path. For `PlayBotsScreen`: your turn / bot thinking / raise unavailable / showdown / fold-around / loading. State-specific bugs are exactly what previews catch.
3. **Screens that take a `ViewModel` directly aren't previewable.** Extract a stateless `XxxScreenContent` taking raw inputs (state values + callbacks); the public `XxxScreen(viewModel)` becomes a thin wrapper that collects state and delegates. Previews target the content composable.
4. **Use `PreviewContent { ... }`** from `:libraries:ui` as the wrapper — it provides theme, clock, build info, and dialog host so previews match runtime.
5. **Import `@Preview` from** `org.jetbrains.compose.ui.tooling.preview.Preview` (multiplatform), not the Android-only one.
6. **Name previews `<ScreenName>Preview_<StateDescription>`** so the Studio preview pane lists them readably.

```kotlin
@Preview
@Composable
private fun PlayBotsScreenPreview_YourTurnPreflop() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = previewActive()),
            onAction = {},
            onBack = {},
        )
    }
}
```

**Sample-data factories** for complex state types belong as `private fun preview<Thing>()` helpers in the same file as the screen — `@Preview`-only. Don't reuse as test fixtures (real tests build state through the engine).

**Repository / Flow dependencies in previews:** define a small `private class Preview<Type>` in-file that returns canned values. See `PreviewConfigOverrideRepository` in `QaMenuScreen.kt`.

## Cross-cutting state in Compose

When something (a service, a setting, a theme value) is needed by every composable in a subtree but doesn't belong on the screen-level ViewModel, prefer a `staticCompositionLocalOf` over threading parameters. Provide once at the subtree root:

```kotlin
val LocalMyService = staticCompositionLocalOf<MyService> { NoopMyService }

// At the screen root:
CompositionLocalProvider(LocalMyService provides realService) {
    HorizontalPager(...) { … }
}
```

Default to a noop, never `error("not provided")`. Keeps `@Preview` and unit tests trivial.

## Remote-tunable values (`:libraries:config`)

Anything that should change without shipping a build — min supported version, maintenance banner, XP multipliers, timer defaults, feature kill switches — goes through `:libraries:config`. Don't roll a parallel system.

Declare values on a `FeatureConfig` subclass; they automatically appear in the debug QA menu (`features/profile/impl/.../QaMenuScreen.kt`) for per-session override.

```kotlin
class UpgradeConfig(configMap: AppConfigMap) : FeatureConfig(
    featureName = "upgrade",
    configMap = configMap,
) {
    val minSupportedVersionCode by featureValue(default = 1)
    val maintenanceMode by featureValue(default = "off")
}
```

Resolution cascade: debug override → server override → QA override → default. Hard-coded defaults always exist so the app works offline / cold-start. Server-side, `:apps:server` serves `GET /v1/app-config` from `AppConfigSource`.

## Decisions log

`docs/decisions.md` is an append-only architectural decisions log. **Add an entry whenever you make a non-trivial architectural call** (new module boundary, choice of library, scope cut, schema shape). Each entry: date, the decision, alternatives considered, and *why*. Future agents (and the user) read this to understand the shape of the codebase without re-deriving every call.

## Design system (DS-first rule)

**Every screen must lean on the design system. Don't hand-tune one-off styling.**

The DS lives in `:libraries:ui` — colors, surface tokens, typography, spacing, and primitives (`ChipBadge`, `XpBadge`, `LastActionPill`, `ListSection`, `BottomSheet`, etc.). Surfaces from `AppTheme.colors.{surfacePrimary, surfaceSecondary, surfaceTertiary, surfaceDisabled, accentPrimary, danger, ...}`. Typography from `AppTheme.typography.{Heading, Body, Display}`.

**Concrete rules:**

1. **No raw `Color.White.copy(alpha = X)` for surface backgrounds.** Use a `surface*` token. Alpha-on-white produces a one-off shade per callsite; screens drift apart and the DS becomes nominal. Surfaces, pills, cards, callout boxes — all `AppTheme.colors.surface*`. Early V1 hand-tuned alphas caused real bugs (chip-pill cutoff, action-pill-overlap).
2. **Reuse existing primitives before writing a new one.** `ChipBadge` / `XpBadge` / `RankBadge` for chip-style affordances. `ListSection` / `ListSectionItem` for settings rows. `BottomSheet` for slide-up sheets. `Dialog` for modals (with `maxHeightFraction` for tall scrollable content). `Screen` for the outer scaffold. If you're about to write a `Box { background, clip, padding, Text }` for the third time, lift it to `:libraries/ui` first.
3. **Borders, dividers, emphasis lines** use `AppTheme.colors.border` / `borderSecondary` — not hand-tuned white alpha.
4. **Poker-game-specific visual artifacts** (chip-gold, card-back-blue, suits) live in `PokerPalette` in `:libraries:ui/system/color/`. Anything semantic (background, surface, accent, text) uses theme tokens.
5. **Spacing comes from `Dimension`** tokens (`Dimension.D200`, `D400`, `D800`, …) when you have several at once; one-off `dp` literals are fine for small offsets. Corner radii from `Radii` tokens — numeric `R300/R400/R500/R600`, semantic aliases `Banner/Callout/Card/Button`. Pass `Radii.X.shape` to `Modifier.clip` / `border` / `Shape` parameters; there's also a `Modifier.clip(radius)` overload.
6. **The DS isn't frozen — extend it when tokens don't fit.** If you reach for `RoundedCornerShape(12.dp)` or `Color(0xFF...)` or `Box { background, clip, padding }` because nothing in `:libraries:ui` matches, *add the missing token/primitive* in `:libraries:ui` and use it from the callsite — don't hand-tune a one-off. Same for `Dimension`, `Radii`, `AppTheme.colors`, `AppTheme.typography`. New tokens are cheap; drift is not.
7. **Identity-bearing content animates between states; it doesn't snap.** Avatars, badges, ranks, chip totals, XP, hero stats — anything that *represents* the user or their progression should transition on change (fade + slight scale-in feels right for emoji/icon swaps; counters use the chip/XP animators). The transition belongs in the DS primitive, not the screen: bake `AnimatedContent` (or the matching animator) into the component so every surface gets it for free and the feel never drifts. `AvatarCircle` is the worked example — emoji/initial swaps animate via `AnimatedContent` with `fadeIn() + scaleIn(0.75f) togetherWith fadeOut()`; pickers, hero previews, and seat avatars all inherit the same feel by routing through it.

When in doubt: "could I drop this into another screen and have it look at home?" If the answer is "only if I retune the alpha values" — extract it to the DS first.

### `Base*` + opinionated DS components

For any DS component big enough that a one-off caller might want to escape the defaults, expose **two layers**: opinionated `<Name>(...)` for the 99% path, raw `Base<Name>(...)` for genuine one-offs. Dialog/BottomSheet are the worked examples; future big primitives (overlays, banners, full-screen sheets, composite widgets) follow the same shape.

- **Opinionated layer** owns DS decisions: surface tokens, padding, typography, default radius/shape, animation feel. Keeps screens visually coherent. Most callers never need anything else.
- **`Base*` layer** owns mechanics — state, layout skeleton, scrim/sheet behaviour, dismissal wiring — and nothing about how it *looks*. Gated by `@LowLevelDSComponent` (a `RequiresOptIn` warning shared across the DS, so callers learn one opt-in signal regardless of which family they're escaping). Warning, not error — error friction pushes callers back to hand-rolled `Box { background, clip, padding }`, which is worse than a deliberate `Base*` use.

When the opinionated default doesn't fit, *extend it* — add a content slot, expose an override, ship a sibling overload — before reaching for `Base*`. If a second screen ever needs the same escape, lift their shared shape into the opinionated layer.

**Current pairs:**
- `Dialog` / `BaseDialog` — center modal. Most callers want `Dialog`.
- `BottomSheet` / `BaseBottomSheet` — slide-up sheet. Most callers want `BottomSheet`. (`HandRankingsCheatSheet` is on `BaseBottomSheet` because the opinionated wrapper doesn't yet expose the content shape it needs — should resolve by extending `BottomSheet`, not entrenching the escape. See `docs/todo.md`.)

Top-edge emoji bubbles attach to both — dialogs via `emoji = dialogEmoji("🎉")`, sheets via `dragHandle = BottomSheetDragHandle.Emoji(emoji = "🎉")`. Theme-aware construction goes through `dialogEmoji(...)` so defaults always apply.

## Coding Guidelines

- Code like a staff engineer.
- **Build the best thing, not the smallest change.** This is an unshipped, greenfield codebase, and the goal is systems that are **scalable, maintainable, and production-ready**. Before implementing, take a step back and ask what's genuinely best for the project and the user experience — and if the right answer is to restructure, replace, or rebuild something already here, do that rather than stacking a minimal patch on top of what exists (that's how a codebase rots into "trash stacked on trash"). Surface the cheaper option if one exists, but recommend the best one and say why. Minimal-first is for hotfixes or when the owner explicitly asks for speed — it is never the default.
- **Fix bugs test-first: reproduce, then fix.** Start a bug fix by writing a test that fails *because of the bug* (red), then make it pass (green). A failing test proves you found the real cause rather than guessing, proves the fix actually works, and stays behind as a permanent regression guard. If you can't reproduce the bug in a test, that's a signal the test harness is missing something — build that first (MP-18 added deck control for exactly this reason). `Mp13ConservationTest` is the worked example: red at `expected 5000 / was 10000`, green after the fix.
- **If you needed telemetry that wasn't there, add it as part of the fix.** When diagnosing a bug (or triaging feedback) you find that a log/breadcrumb/span which *would have* pinned the cause is missing, close that gap in the same change — so the next person reads the answer instead of re-deriving it. Place it at the decision point that was opaque (the render projection, the "celebration enqueued vs skipped" branch, the refusal→user-event mapping), and log enough to disambiguate the cause. **But never log in a tight loop** — no per-frame, per-recomposition, per-engine-tick, per-bot-iteration, or per-flow-emission lines — and nothing that would flood or DDOS our telemetry pipeline (Sentry breadcrumbs, Loki, Tempo). One carefully-placed, queryable line at the branch that matters beats a stream of noise that buries it and costs us ingest. When in doubt, log once per hand / per user action / per state transition, at Info if it's something we'd want to query in prod.
- Use `Catching { }` from `libraries/core` instead of `runCatching`. `runCatching` swallows `CancellationException`, which breaks structured concurrency in suspend functions and coroutine scopes. `Catching` rethrows it (preserving `TimeoutCancellationException`). Use it everywhere for consistency, not just inside coroutines.
- Every HTTP call flows through one of three `NetworkClient` helpers: `authedCall("description") { client -> … }`, `unauthedCall(...)`, or `authedWebSocketSession(...)`. Shared code path: pre-flight `awaitAuthReady()` (for authed variants so the request timeout doesn't tick during auth bootstrap), structured failure logging, opt-in `RetryPolicy`. Default is `RetryPolicy.None` — pass `RetryPolicy.idempotent()` only when the endpoint is genuinely idempotent (upsert / idempotency-key / read). The name is documentation: choosing it affirms the server can safely process the same request twice. Non-idempotent POSTs (purchases, room creation) leave retry at `None`. Direct access to `NetworkClient.client` / `authenticatedClient` is gated by `@InternalNetworkingApi`.
- **Don't litter the code with comments.** Default to none. Let clear names and small functions carry the meaning — don't narrate what the code already says (`// loop over seats`, `// set the flag`, section banners), don't restate a function's signature above it, and don't leave AI-style "here's what I changed / why I picked this" notes. A comment earns its place only when it explains something the code *can't*: a non-obvious gotcha, a regression it guards, or a "why this way and not the obvious way." If you're tempted to explain a tricky block, prefer extracting a well-named function. When you touch code that's already over-commented, strip the noise as you go.
- Custom UI components in `libraries/ui` — avoid Material directly.
- **Every user-facing string lives in `:libraries:resources`** (`libraries/resources/src/commonMain/composeResources/values/strings.xml`). Read via `stringResource(Res.string.foo, args)` in a Composable or `getString(Res.string.foo, args)` in a suspend / non-Composable context. Naming convention: `{surface}_{role}_{specifier}` (e.g. `lobby_idle_create_button`). No inline `"…"` strings in Composables, dialogs, or VM-emitted user-visible copy. Exceptions: glyph-only typography (✓, emoji icons that *are* the affordance), preview-only sample data, error message keys that come from the server. New string → new XML entry, not an inline literal. Module needs `implementation(projects.libraries.resources)` if it's not already in there.
- Never use backslash escapes or em dashes in `strings.xml`: Compose-MP renders `\'` literally so write `it's` not `it\'s` (`\n` is fine), and use a hyphen or rephrase instead of an em dash.
- Check `ComposeApp.h` for Swift names of Kotlin types before using in Swift.

## iOS Notes

- iOS framework compiled from `apps/compose`, embedded as `ComposeApp.xcframework`.
- Swift types passed to Kotlin via `IosAppComponentFactory.create(...)`.
- Reference `apps/compose/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework/Headers/ComposeApp.h` for generated Swift interfaces.
- **Use `@ObjCName("TypeName", exact = true)` on Kotlin types used from Swift** to give stable names that survive project rename:
  ```kotlin
  @file:OptIn(ExperimentalObjCName::class)
  import kotlin.experimental.ExperimentalObjCName
  import kotlin.native.ObjCName

  @ObjCName("MyType", exact = true)
  interface MyType { ... }
  ```
  `exact = true` prevents the module prefix (without it the Swift name becomes `<ModuleName><ObjCName>`, e.g. `KmptemplateMyType`).

## Key Files

| Purpose | Path |
|---------|------|
| User model | `libraries/cards/src/.../User.kt` |
| SEAViewModel | `libraries/flowroutines/src/.../SEAViewModel.kt` |
| App DI | `apps/compose/src/.../AppComponent.kt` |
| iOS entry | `apps/ios/iosApp/iOSApp.swift` |
| Swift↔Kotlin patterns | `docs/practices/swift-kotlin.md` |
| Architecture decisions log (append-only) | `docs/decisions.md` |
| Remote config + QA menu | `libraries/config/...`, `features/profile/impl/.../QaMenuScreen.kt` |
