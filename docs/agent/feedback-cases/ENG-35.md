# ENG-35 — Banned circuit breaker + stop reporting expected 4xx as errors

**Signal:** Sentry `CARDS-BG` — `ClientRequestException: POST /v1/equipment/sync → 403 {"reason":"banned","until":...,"appealUrl":...}`. A banned user's client keeps firing normal requests; each 403 is logged to Sentry as an error, and there is no local short-circuit or un-ban recovery.

Sibling to **AUTH-29** (that one is the "valid JWT, no `auth.users` row → raw 500 + retry-storm" path). This item is the clean-403 **banned** path, which already surfaces via `AccessDeniedScreen`. The problems here are: (1) error-telemetry noise, (2) no circuit breaker so every subsequent call fires another doomed 403, (3) no way out of the blocking screen without an app relaunch.

## How the code works today

- **Ktor client choke point:** `libraries/networking/impl/src/commonMain/kotlin/com/cards/libraries/networking/impl/NetworkClientImpl.kt`. Both `client` (unauthed) and `authenticatedClient` funnel through the private `HttpClientConfig<*>.applyCommonConfig(...)` (ContentNegotiation, `HttpResponseValidator`, `HttpTimeout`, `DefaultRequest`, `expectSuccess = true`). Single place to install a plugin.
- **403/banned detection:** `applyCommonConfig` → `HttpResponseValidator { handleResponseExceptionWithRequest {...} }`. On a `Forbidden` `ResponseException` it decodes the `AccessDeniedWire` envelope (`{reason, until, appealUrl}`) and calls `accessDeniedBus.signalDenied(...)`.
- **The bus is fire-and-forget:** `AccessDeniedBusImpl` is a `MutableSharedFlow(replay = 0)`. There is **no persisted/queryable banned flag** — the signal is consumed once. `AppViewModel` forwards it to `AccessDeniedScreen`, which is dismiss-blocked (`BackHandler { doNothing() }`) with no recovery path; recovery is relaunch-only.
- **Why CARDS-BG reaches Sentry:** `UserScopedSyncCoordinator.kt` (~line 65) calls `.logOnFailure { ... }` → `Catching.logOnFailure` logs at **Error** (`KLog.e`). `SentryLogTree.shouldCaptureEvent` captures anything ≥ Error unless the throwable `isExpectedControlFlow` or `isOfflineError()`. A banned `ClientRequestException` is neither, so `Sentry.captureException` fires. (`NetworkCall.logFailure` logs the same exception at Warn, below threshold, so it is not the culprit.)
- **Existing reuse pattern:** `libraries/core/.../ThrowableExtensions.kt` has `interface ExpectedControlFlow` + `Throwable.isExpectedControlFlow`. `AuthUnready` implements it, which is exactly why the auth pre-flight short-circuit (`NetworkCall.shortCircuitOrNull`) produces **no** telemetry. The breaker reuses this seam.

## Plan (ordered)

1. **Queryable banned-state holder** in `:libraries:networking`. New `BannedState` interface (`val isBanned: Boolean` for the synchronous interceptor read, `val state: StateFlow<Denial?>`, `setBanned(denial)`, `clearBanned()`) + `BannedStateImpl` (`@SingleIn(AppScope::class) @ContributesBinding`, `MutableStateFlow`-backed) mirroring `AccessDeniedBusImpl`. Set the flag in `NetworkClientImpl.signalAccessDeniedIfEnveloped` right after `accessDeniedBus.signalDenied(...)`. Decide persistence: back it with the storage layer so a relaunch-while-banned doesn't fire a doomed burst before the first 403; in-memory is an acceptable v1 if persistence is scoped out (note it).
2. **Circuit-breaker plugin** `BannedCircuitBreakerPlugin.kt` via `createClientPlugin`. On each request: if `bannedState.isBanned` and `context.url.encodedPath` is not on the allowlist, throw `BannedShortCircuit : ExpectedControlFlow` and never touch the wire. Allowlist = small prefix set: `/v1/me` (status), `/v1/app-config`; also consider `/v1/reports`. Match `/v1/me` exactly for the status GET, not the `/v1/me/*/sync` writes.
3. **Install on both clients** in `applyCommonConfig`, ordered **before** `Auth` so a banned request is cut before the bearer plugin loads/refreshes a token. Thread `BannedState` into `NetworkClientImpl`'s constructor like `accessDeniedBus`.
4. **Un-ban recovery:** the allowlisted `/v1/me` still goes through; on a 2xx from `/v1/me` call `bannedState.clearBanned()` (extend the existing `validateResponse` in `applyCommonConfig`). `AppViewModel` observes `bannedState.state` so the blocking screen dismisses when the flag clears. **Product decision to flag:** nothing calls `/v1/me` on its own while the screen is up, so auto-recovery needs a poll / warm-foreground refresh that hits `/v1/me`; otherwise recovery stays relaunch-only. The flag flips correctly either way.
5. **Stop reporting expected 4xx as errors (broader cleanup):** add `Throwable.isExpectedClientError()` in `:libraries:networking` (true for `ResponseException` with `Unauthorized`/`Forbidden`) and extend `SentryLogTree.shouldCaptureEvent` to drop it, alongside the existing `isExpectedControlFlow`/`isOfflineError()` exclusions. Confirm `CancellationException` (user-cancellations) never reaches `KLog.e` (`ThrowableExtensions.shouldNotBeCaught` + `Catching` re-throw); add to the exclusion if any path leaks it.

## Files
- new `libraries/networking/src/commonMain/.../BannedState.kt`
- new `libraries/networking/impl/src/commonMain/.../BannedStateImpl.kt`
- new `libraries/networking/impl/src/commonMain/.../BannedCircuitBreakerPlugin.kt` (+ `BannedShortCircuit`)
- `libraries/networking/impl/src/commonMain/.../NetworkClientImpl.kt` (inject state, install plugin, set flag, clear on `/v1/me` 2xx)
- `libraries/networking/src/commonMain/.../OfflineErrors.kt` (add `isExpectedClientError()`)
- `libraries/cards/impl/src/commonMain/.../logging/SentryLogTree.kt` (extend exclusion)
- `apps/compose/src/commonMain/.../AppViewModel.kt` (observe `bannedState.state`)

## Tests (test-first)
- `BannedCircuitBreakerTest`: banned + non-allowlisted (`/v1/equipment/sync`) throws `BannedShortCircuit` and MockEngine records **zero** requests; `/v1/me` still hits the engine. Mirror `AccessDeniedRoutingTest` MockEngine wiring.
- Un-ban flip: a `/v1/me` 200 calls `clearBanned()`; subsequent non-allowlisted calls pass.
- `signalAccessDeniedIfEnveloped` sets `BannedState` in addition to firing the bus.
- `SentryLogTreeTest`: `ResponseException` at 403/401 at Error → `shouldCaptureEvent` false; a 500 still true (follows `SentryLogTreeConnectivityTest`).
- `BannedStateImplTest`: set/clear transitions + `StateFlow` emission.
