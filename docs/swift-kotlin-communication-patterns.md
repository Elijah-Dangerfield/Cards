# KMP Strategies

This guide tracks the two recurring decisions you’ll make when mixing Kotlin Multiplatform with Swift: (1) how to expose Kotlin code to Xcode targets and (2) which pattern to use when Kotlin and Swift need to talk to each other.

## 1. Making Xcode Frameworks from Kotlin Code

- The `apps/compose` Gradle module uses the shared convention plugin to declare a `framework { baseName = "ComposeApp" ... }` block. Gradle compiles the Kotlin sources plus the modules listed in its `export(...)` calls into `ComposeApp.xcframework` under `apps/compose/build/xcode-frameworks/`, and Xcode’s Embed-and-Sign phase pulls that artifact into the app target.
- Because exports are transitive (with the occasional need for manual `export` entries), most shared classes become available to Swift via `import ComposeApp`. That’s why the iOS host app can call repositories, logging utilities, and DI facades directly.
- Any module can publish its own framework. Add another `binaries.framework("<Name>") { export(<module>) }` stanza, run the XCFramework tasks, and point another Xcode target (like an extension) at the resulting `<Name>.xcframework`. This keeps extensions lightweight—you can hand them just `libraries/core` for logging instead of the entire app surface.

## 2. Swift ↔ Kotlin Communication Patterns

A quick toolbelt for deciding how shared Kotlin code and native Swift code should talk to each other inside Virtu. Keep these patterns lightweight and pick the one that matches the direction of ownership and who truly controls the implementation.

### 2.1 Kotlin Twin

- **Definition:** Define an interface in `commonMain`, and provide platform-specific Kotlin implementations in `androidMain` and `iosMain`. Bind each implementation through DI.
- **Use When:** Kotlin owns the feature, but it needs different platform primitives (storage, sensors, OS services).
- **Notes:** Keeps the shared layer platform-agnostic; iOS implementation stays in Kotlin files under `iosMain` so it participates in DI just like Android.

### 2.2 Swift Twin

- **Definition:** Same interface lives in `commonMain`, Android still provides the Kotlin implementation, but iOS leaves the Kotlin side empty. Instead, Swift creates the concrete implementation, passes it into `App()` (or another entry point), and DI binds that Swift object.
- **Use When:** The real implementation must be written in Swift (e.g., Screen Time APIs, UIKit-only surfaces) but Kotlin still needs to depend on the abstraction.
- **Notes:** Callback contracts fall under this bucket—Swift conforms to the generated protocol and Kotlin stores it through DI.

### 2.3 Component Access (Direct DI Surface)

- **Definition:** Allow Swift to create or receive the `IosAppComponent` and pull DI-managed objects straight from it. The component can expose raw Kotlin classes (repositories, use cases) or purpose-built bridge/facade classes tailored to Swift.
- **Use When:** Swift needs to call into Kotlin services that already live inside the DI graph (e.g., repositories, feature facades) without re-implementing anything natively.
- **Flow:** Swift assembles its platform deps, calls a Kotlin factory like `IosAppComponentFactory.create(...)`, keeps the component reference, and grabs what it needs via typed getters (`component.activityFeatureEntry`, `component.swiftBridge()` etc.).

### 2.4 Channel Conduit (Two-Way Surface)

- **Definition:** Share a single object (channel, bus, event hub) that both Kotlin and Swift hold. Kotlin pushes updates or reads inputs; Swift does the opposite.
- **Use When:** You need bidirectional communication (events, timers, state streaming) without exposing raw coroutine primitives.
- **Implementation Ideas:**
  - Kotlin defines `RuleStateChannel` interface with `send`/`observe` methods; Swift implements it and passes it in (Swift Twin flavor).
  - Or Kotlin provides the channel (DI-managed), and Swift just subscribes/acts on it while Kotlin keeps sending events.

### 2.5 API Adapters

- **Definition:** Wrap an existing Kotlin API with a friendlier surface for Swift (or vice versa). These adapters massage naming, threading, async models, or parameter shapes.
- **Use When:** A repository/use case technically works from Swift, but the ergonomics are rough (Flows, coroutines, deeply nested lambdas).
- **Examples:** Converting Kotlin `Flow` to a Swift-native async sequence, exposing explicit `start()/stop()` methods around a suspend function, or wrapping multiple Kotlin calls behind a single Swift-facing method.

### 2.6 How to Choose Quickly

1. **Does Kotlin own the implementation, but needs OS hooks?** → Kotlin Twin.
2. **Does Swift own the implementation?** → Swift Twin.
3. **Does Swift simply need to call existing Kotlin services?** → Component Access + (optional) Adapter wrapper.
4. **Do both sides need to exchange events/state?** → Channel Conduit, potentially layered on a Twin.
5. **Is the API technically callable but awkward?** → Add an Adapter.

Remember: iOS can always reach any DI-managed Kotlin object via the component. Whether that object is an old repo or a Swift-specific bridge class is up to the feature. Pick the simplest pattern that satisfies the direction of control and keep the naming consistent so future contributors recognize the intent immediately.
