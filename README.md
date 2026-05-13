# Cards

A Kotlin Multiplatform template with a clean, modular architecture using Compose Multiplatform, Room database, and a base ViewModel that encourages unidirectional data flow.

## Build & Run

```shell
# Android
./gradlew :apps:compose:assembleDebug

# iOS - compile Kotlin framework
./gradlew :apps:compose:compileKotlinIosSimulatorArm64

# iOS - or open in Xcode
open apps/ios/iosApp.xcodeproj
```

## Project Structure

```
apps/compose/          # KMP entry point (Android + iOS)
apps/ios/              # Swift/Xcode wrapper
features/<name>/       # Routes and public API
features/<name>/impl/  # Screens and ViewModels
libraries/<name>/      # Interfaces
libraries/<name>/impl/ # Implementations
```

### Architecture Rules

These are enforced at configuration time by the convention plugins — a violating `implementation(project(...))` fails the Gradle sync.

- **Only `:apps:*` may depend on `*:impl`.** Impl modules are DI wiring composed by the app, not consumed by other features or libraries.
- **Feature `api` modules may not depend on other feature `api` modules.** api-to-api across features becomes a cycle the moment someone adds the reverse edge. Shared types go into a library. Sub-modules of the same feature (`:features:foo:storage` → `:features:foo`) are fine.
- **Shared code belongs in libraries.** The `:libraries:storage` module is the one exception where `:impl` is shared — it owns the `AppDatabase`.

### Creating New Modules

```shell
./scripts/create_module
```

| Plugin | Use Case |
|--------|----------|
| `cards.kotlin.multiplatform` | Pure Kotlin modules |
| `cards.compose.multiplatform` | Kotlin + Compose UI |
| `cards.feature` | Feature modules |

## Architecture Patterns

### ViewModel (Unidirectional Data Flow)

ViewModels extend `SEAViewModel` which enforces **State-Event-Action** unidirectional data flow:

```kotlin
class MyViewModel : SEAViewModel<State, Event, Action>(initialStateArg = State()) {
    override suspend fun handleAction(action: Action) {
        when (action) {
            is Action.Load -> action.updateState { it.copy(loading = true) }
            is Action.Submit -> {
                // Do work, then send one-shot event
                sendEvent(Event.NavigateBack)
            }
        }
    }
}
```

- **State**: Immutable data class representing UI state
- **Event**: One-shot side effects (navigation, toasts, etc.)
- **Action**: The only way to mutate state via `action.updateState { }`

### Dependency Injection

Uses [kotlin-inject-anvil](https://github.com/amzn/kotlin-inject-anvil):

```kotlin
// Bind implementation to interface
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class MyRepositoryImpl : MyRepository

// Multibinding for feature entry points
@ContributesBinding(AppScope::class, multibinding = true)
class MyFeatureEntryPoint : FeatureEntryPoint
```

### Navigation

Routes are `@Serializable` data classes extending `Route`:

```kotlin
@Serializable
data class ProfileRoute(val userId: String) : Route

// Register in FeatureEntryPoint.buildNavGraph()
screen<ProfileRoute> { backStackEntry -> 
    ProfileScreen(userId = backStackEntry.toRoute<ProfileRoute>().userId)
}
```

Supports `screen`, `bottomSheet`, and `dialog` destinations.

## iOS Integration

The iOS app embeds a Kotlin framework compiled from `apps/compose`. Swift types can be passed into Kotlin's DI graph via `IosAppComponentFactory.create(...)`.

When exposing Kotlin types to Swift, use `@ObjCName` for stable naming:

```kotlin
@ObjCName("MyType", exact = true)
interface MyType { ... }
```

See [Swift-Kotlin Communication Patterns](docs/swift-kotlin-communication-patterns.md) for detailed guidance.

## Coding Guidelines

- Use `Catching { }` from `libraries/core` instead of `runCatching`
- Custom UI components go in `libraries/ui`—avoid using Material components directly

## Key Files

| Purpose | Path |
|---------|------|
| App DI Component | `apps/compose/src/.../AppComponent.kt` |
| Base ViewModel | `libraries/flowroutines/src/.../SEAViewModel.kt` |
| iOS Entry Point | `apps/ios/iosApp/iOSApp.swift` |

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
