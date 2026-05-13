# Config API Module

This module centralizes application configuration, feature flags, and experiment toggles for every platform that depends on the Virtu shared code. It provides:

- A strongly typed way to access configuration values (`ConfiguredValue`, `AppConfigMap`).
- Experiment support with control/test values and debug-only flags (`Experiment`).
- Streams for observing config changes at runtime (`AppConfigFlow`).
- Override plumbing for QA and local debugging (`ConfigOverrideRepository`).
- Helpers for safely parsing nested config documents (`MapExt`).

## Core concepts

### `AppConfigMap`

Represents a snapshot of the merged configuration. It exposes typed accessors:

```kotlin
def configVersion(): Int = config.intValue(ConfigValues.ConfigVersion)
```

- Looks up values using dot-delimited paths.
- Honors `ConfiguredValue.debugOverride` while running in debug builds.
- Falls back to the provided default when a value is missing or fails to cast.
- Provides `experiment()` for toggles defined via `Experiment` objects.

### `ConfiguredValue<T>`

Defines a single configuration entry with metadata:

- `displayName` and optional `description` for QA dashboards.
- `path` (defaults to the lower-camel-case class name) used inside the config map.
- `default` fallback value.
- `showInQADashboard` and `debugOverride` flags to surface values or force a local override.

Subclass it as an `object` and reference it through `AppConfigMap.value(...)` or the numeric helpers (`intValue`, `longValue`, etc.).

### `Experiment<T>`

Models a feature experiment. Each experiment declares:

- `id`, which becomes the `experiments.{id}` path in the config document.
- `control` and `test` values plus an optional `default` override.
- `isDebugOnly` to prevent non-debug builds from reading debug experiments.

The `AppConfigMap.experiment()` helper enforces the debug-only rule and returns the configured bucket value or the default.

### `AppConfigRepository`

Abstraction for any source of truth (remote config service, bundled JSON, etc.). Responsibilities:

- `config()` returns the latest known map synchronously.
- `configStream()` exposes a cold `Flow<AppConfigMap>` that emits updates.

### `AppConfigFlow`

A thin wrapper around `AppConfigRepository.configStream()` so you can inject `Flow<AppConfigMap>` directly. Use it anywhere reactive updates are required (e.g., feature screens reacting to live flag changes).

### `ConfigOverrideRepository`

Stores QA/debug overrides. Typical implementations keep overrides in local storage, merge them ahead of the remote config, and expose a stream so the UI can update when QA edits values.

### `ConfigOverride`

Simple value object that associates a fully qualified config `path` with a strongly typed `value`.

### `EnsureAppConfigLoaded`

`fun interface` used to guarantee initial config fetch before sensitive flows start. Implementations usually combine remote fetch, override hydration, and error handling wrapped in a `Catching<Unit>`.

### `MapExt`

Utility extensions used by the module:

- `Map.getValueForPath` recursively traverses nested maps and performs type-safe casting with helpful debug logging via `Catching`.
- `plusIf`/`plusIfNotNull` helpers for building maps conditionally.
- `mergeWith` and `List<Map>.toMergedMap()` for combining layered configs (e.g., defaults + remote + overrides).

## Typical data flow

1. `AppConfigRepository` fetches the remote/bundled document as a nested map.
2. `ConfigOverrideRepository` applies local overrides and merges them using `mergeWith`.
3. The merged map is wrapped in an `AppConfigMap` implementation.
4. `AppConfigFlow` exposes a `Flow<AppConfigMap>` for consumers that need live updates.
5. Call sites read typed values through `AppConfigMap.value(...)`, `intValue(...)`, or `experiment(...)`.

## Defining a new config value

```kotlin
object MaxLoginAttempts : ConfiguredValue<Int>() {
    override val displayName = "Max Login Attempts"
    override val path = "auth.maxLoginAttempts"
    override val default = 5
    override val debugOverride = 10 // optional
}

class LoginViewModel @Inject constructor(private val config: AppConfigMap) {
    fun lockAccount(tries: Int) = tries >= config.intValue(MaxLoginAttempts)
}
```

## Defining an experiment

```kotlin
object OnboardingRevamp : Experiment<Boolean>() {
    override val displayName = "Onboarding Revamp"
    override val id = "onboarding_revamp"
    override val control = false
    override val test = true
    override val isDebugOnly = false
    override fun resolveValue(): Boolean = control
}

if (config.experiment(OnboardingRevamp)) {
    showNewFlow()
} else {
    showLegacyFlow()
}
```

## Working with overrides

- Use `ConfigOverrideRepository.addOverride(...)` to persist QA edits.
- Merge overrides ahead of remote values using `Map.mergeWith` utilities to guarantee the most recent override wins.
- Because overrides emit via `getOverridesFlow()`, UI panels can observe and reflect live edits without restarting the app.

## Error handling and debugging

- `MapExt` conversions wrap casting logic in `Catching`, logging failures via `logOnFailure` and throwing in debug builds (`throwIfDebug`) to surface misconfigured paths early.
- `Experiment.isDebugOnly` ensures debug experiments never leak to production.
- Use `debugOverride` on `ConfiguredValue` objects for quick local testing without editing the remote config.
