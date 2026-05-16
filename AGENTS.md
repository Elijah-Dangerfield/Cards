# AGENTS.md

Guidelines for AI agents working in the Cards repository.

## Overview

KMP (Kotlin Multiplatform) app with Compose Multiplatform. Modular architecture with Room database, navigation, and SEAViewModel pattern.

This is **Kotlin Multiplatform**—most code is shared, but some platform features (permissions, sensors, native APIs) require platform-specific implementations. When implementing something not inherently cross-platform, follow the patterns in `docs/swift-kotlin-communication-patterns.md`.

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

**Rules** — enforced at Gradle configuration by the convention plugins:

- Only `:apps:*` may depend on `*:impl`. Impls are DI wiring composed by the app, not consumed by other modules.
- Feature `impl` modules may depend on another feature's `api`. Feature `api` modules may **not** depend on other feature `api`s (api-to-api is a cycle risk — shared types go in a library).
- Sub-modules of the same feature (`:features:foo:storage` → `:features:foo`) are allowed.
- `:libraries:storage:impl` is the one shared impl — it owns the `AppDatabase`.

Shared code → libraries. Main modules expose interfaces only; impl modules contain implementations.

## Conventional Commits (required)

Every commit (and every PR title — PRs are squash-merged) must follow [Conventional Commits](https://www.conventionalcommits.org/). Release-please derives the next version bump from commit history.

| Type | When | Version bump |
| --- | --- | --- |
| `feat:` | User-visible new capability | minor |
| `fix:` | Bug fix | patch |
| `perf:` | Perf improvement, user-visible | patch |
| `feat!:` / `BREAKING CHANGE:` | Breaking change | major |
| `refactor:`, `style:`, `test:`, `docs:`, `ci:`, `build:`, `chore:`, `revert:` | No user impact | none |

A local `.githooks/commit-msg` hook enforces this on every commit. The Gradle build fails with an install-hooks message if the hook isn't wired — run `./scripts/install_hooks.sh`.

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

No expect/actual for platform impls—bind different implementations per platform. iOS impls written in Swift get passed into the DI graph via `IosAppComponentFactory.create(...)`.

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

## Navigation

Routes are `@Serializable` data classes extending `Route`. Register in `FeatureEntryPoint.buildNavGraph()`:

```kotlin
screen<MyRoute> { backStackEntry -> MyScreen(...) }
bottomSheet<SheetRoute> { backStackEntry, sheetState -> ... }
dialog<DialogRoute> { backStackEntry, dialogState -> ... }
```

**Use `bottomSheet<>` for transient picker / overlay UIs** (a settings list, a "select an item" sheet) rather than pushing a full screen. The backstack stays one entry deep, the underlying screen is visible under a scrim, and `sheetState.dismiss()` is a clean exit. Reach for full `screen<>` only when the destination is its own context (settings page, detail view).

**Open external URLs via `Router.openWebLink(url)`** — don't roll your own platform `Intent.ACTION_VIEW` / `UIApplication.shared.open` plumbing. The implementation is in `libraries/navigation/impl/.../{Android,Ios,Jvm}WebLinkLauncher.kt` and is already wired into the DI graph and the `Router` interface.

## App-wide state

`AppData` (in `libraries/<projectid>/.../AppCache.kt`) is a `@Serializable` data class persisted via `CacheFactory.persistent`. Add fields here for things like:

- Onboarding flags (`hasUserOnboarded`)
- User-facing setting toggles
- Counters / lightweight telemetry (`feedbacksGiven`, `bugsReported`)

Don't roll a new persistent cache for a single boolean — extend `AppData`. Round-trip is automatic via `versionedJsonSerializer` (missing fields fall back to defaults, so adding a field is non-breaking). For an example wrapper that exposes `StateFlow<Boolean>` for Compose, see how a feature-level store reads `AppCache.updates` and writes via `appCache.update { it.copy(...) }`.

## Compose previews (required for screens)

Every user-facing screen composable needs `@Preview` coverage. Without it, iterating on UI means rebuild → reinstall → navigate to the screen → set up the state by hand — every change. With previews, Android Studio renders the screen instantly and you can flip through every meaningful state.

**The rules:**

1. **Every public screen-level composable** in a `:features:*:impl` module must have at least one `@Preview`. New screens land with previews in the same PR.
2. **Cover the meaningful states**, not just the happy path. For a typed `State` UI, that's one preview per logically distinct rendering — e.g. for `PlayBotsScreen`: your turn / bot thinking / raise unavailable / showdown / fold-around / loading. The bugs that escape tests are usually state-specific; previews catch them.
3. **Screens that take a `ViewModel` directly aren't previewable** as-is. Extract a stateless `XxxScreenContent` that takes raw inputs (state values + callbacks); the public `XxxScreen(viewModel)` becomes a thin wrapper that collects state and delegates. Previews target the content composable.
4. **Use `PreviewContent { ... }`** from `:libraries:ui` as the wrapper — it provides the theme, clock, build info, and dialog host so previews match runtime appearance.
5. **Import `@Preview` from** `org.jetbrains.compose.ui.tooling.preview.Preview` (multiplatform-compatible), not the Android-only one.
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

**Sample-data factories** for complex state types (table state, profile settings, etc.) belong as `private fun preview<Thing>()` helpers in the same file as the screen. They're for `@Preview` only — don't reuse them as test fixtures (real tests build state through the engine).

**Repository / Flow dependencies in previews:** for screens that take a repository or `Flow<…>`, define a small `private class Preview<Type>` in-file that returns canned values. See `PreviewConfigOverrideRepository` in `QaMenuScreen.kt`.

## Cross-cutting state in Compose

When something (a service, a setting, a theme value) is needed by every composable in a subtree but doesn't belong on the screen-level ViewModel, prefer a `staticCompositionLocalOf` over threading parameters. Provide it once at the subtree root:

```kotlin
val LocalMyService = staticCompositionLocalOf<MyService> { NoopMyService }

// At the screen root:
CompositionLocalProvider(LocalMyService provides realService) {
    HorizontalPager(...) { … }
}
```

Default it to a noop, never `error("not provided")`. This keeps `@Preview` and unit tests trivial — they get the noop automatically.

## Remote-tunable values (`:libraries:config`)

Anything that should be changeable without shipping a build — min supported version, maintenance banner, XP multipliers, timer defaults, feature kill switches — goes through `:libraries:config`. Don't roll a parallel system.

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

Resolution cascade: debug override → server override → QA override → default. Hard-coded defaults always exist so the app works offline / cold cold-start. Server-side, `:apps:server` serves `GET /v1/app-config` from `AppConfigSource`.

## Decisions log

`docs/decisions.md` is an append-only architectural decisions log. **Add an entry whenever you make a non-trivial architectural call** (new module boundary, choice of library, scope cut, schema shape). Each entry: date, the decision, the alternatives considered, and *why*. Future agents (and the user) read this to understand the shape of the codebase without re-deriving every call.

## Design system (DS-first rule)

**Every screen must lean on the design system. Don't hand-tune one-off styling.**

The DS lives in `:libraries:ui` — colors, surface tokens, typography, spacing, and primitive components (`ChipBadge`, `XpBadge`, `LastActionPill`, `ListSection`, `BasicBottomSheet`, etc.). Surface colors come from `AppTheme.colors.{surfacePrimary, surfaceSecondary, surfaceTertiary, surfaceDisabled, accentPrimary, danger, ...}`. Typography from `AppTheme.typography.{Heading, Body, Display}`.

**Concrete rules:**

1. **No raw `Color.White.copy(alpha = X)` for surface backgrounds.** Use a `surface*` token. The alpha-on-white pattern produces a one-off shade per call site; the screens drift apart and the DS becomes nominal rather than load-bearing. Surfaces, pills, cards, callout boxes — all `AppTheme.colors.surface*`. The hand-tuned alphas we used in early V1 (chip pills, action pills, info cards) caused real bugs: the chip-pill cutoff and the action-pill-overlap were symptoms of the same "hand-tuned tile" mindset.
2. **Reuse the existing primitives before writing a new one.** `ChipBadge` / `XpBadge` / `RankBadge` for chip-style affordances. `ListSection` / `ListSectionItem` for settings rows. `BasicBottomSheet` for slide-up sheets. `Dialog` for modals (with `maxHeightFraction` for tall scrollable content). `Screen` for the outer scaffold. If you're about to write a `Box { background, clip, padding, Text }` for the third time, lift it to `:libraries/ui` first.
3. **Borders, dividers, and emphasis lines** use `AppTheme.colors.border` / `borderSecondary` — not hand-tuned white alpha.
4. **Pokemon-game-specific visual artifacts** (chip-gold, card-back-blue, suits) live in `PokerPalette` in `:libraries:ui/system/color/`. Anything semantic (background, surface, accent, text) uses the theme tokens.
5. **Spacing comes from `Dimension`** tokens (`Dimension.D200`, `D400`, `D800`, etc.) when you have several values at once; one-off `dp` literals are fine for small offsets.

**The DS-first instinct is what keeps screens from feeling like a-grab-bag-of-Compose.** When in doubt, ask "could I drop this into another screen and have it look at home?" If the answer is "only if I retune the alpha values" — extract it to the DS first.

## Coding Guidelines

- Code like a staff engineer
- Use `Catching { }` from libraries/core instead of `runCatching`. `runCatching` swallows `CancellationException`, which breaks structured concurrency in suspend functions and coroutine scopes. `Catching` rethrows it (while preserving `TimeoutCancellationException`). Use it everywhere for consistency, not just inside coroutines.
- No comments in code
- Custom UI components in libraries/ui—avoid Material directly
- Check `ComposeApp.h` for Swift names of Kotlin types before using in Swift

## iOS Notes

- iOS framework compiled from `apps/compose`, embedded as `ComposeApp.xcframework`
- Swift types passed to Kotlin via `IosAppComponentFactory.create(...)`
- Reference `apps/compose/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework/Headers/ComposeApp.h` for generated Swift interfaces
- **Use `@ObjCName("TypeName", exact = true)` on Kotlin types used from Swift** to give stable names that won't change when project is renamed:
  ```kotlin
  @file:OptIn(ExperimentalObjCName::class)
  import kotlin.experimental.ExperimentalObjCName
  import kotlin.native.ObjCName
  
  @ObjCName("MyType", exact = true)
  interface MyType { ... }
  ```
  Note: The `exact = true` parameter prevents module prefixes from being added. Without it, the Swift name would be `<ModuleName><ObjCName>` (e.g., `KmptemplateMyType`).

## Key Files

| Purpose | Path |
|---------|------|
| User model | `libraries/cards/src/.../User.kt` |
| SEAViewModel | `libraries/flowroutines/src/.../SEAViewModel.kt` |
| App DI | `apps/compose/src/.../AppComponent.kt` |
| iOS entry | `apps/ios/iosApp/iOSApp.swift` |
| Swift↔Kotlin patterns | `docs/swift-kotlin-communication-patterns.md` |
| Architecture decisions log (append-only) | `docs/decisions.md` |
| Remote config + QA menu | `libraries/config/...`, `features/profile/impl/.../QaMenuScreen.kt` |

