# Compose UI testing spike — full-app vs nav-graph

**Date:** 2026-06-24
**Status (2026-07-04):** proposal only — none of this has been built (no Robolectric/compose-uiTest infra, no `TestAppComponent`, no `androidUnitTest` source set in `apps/compose`). The gap it describes (UI-layer navigation wiring is untested) still exists. Kept as the reference for when this work is picked up.
**Question:** should we test the navigation/UI layer by standing up the real app
(real screens, real repos, real navigator) rather than stubbing — and if so, at
what level? Prompted by two MP-leave bugs (2026-06-24) that lived *between* the
ViewModel and the navigator and slipped past every test we had.

This replaces an earlier, narrower spike that stub-tested the router primitive in
isolation. That approach was wrong: it stubbed the very screens whose wiring was
broken, so it wouldn't have caught the bugs. The real question is whether to use
real implementations end to end, which is what this doc evaluates.

## The two viable options

Both render real Compose under a JVM test runtime (Robolectric — no emulator),
drive via Compose semantics (`onNodeWithText("Leave").performClick()`), and use
**real repositories** pointed at the existing in-process server. They differ in
how much of the app they stand up.

- **Option 2 — nav graph + real screens, no app shell.** Build a `NavHost`,
  register the *real* feature entry points' `buildNavGraph(router)`, with a real
  `DelegatingRouter`. Skip `App()`'s chrome (bottom bar, dialog/snackbar hosts,
  app guards, splash) and skip the boot gate entirely. Catches entry-point
  wiring (the `onBack` / `OpponentsLeft` lambdas where the bugs lived).

- **Option 3 — the real `App(appComponent)`.** Stand up the whole root via a
  test DI component, let it boot to Home, drive it like a user. Catches
  everything Option 2 does *plus* app-root behavior: bottom-bar tab switching +
  saved-stack restore, dialog/snackbar hosts, the SessionExpired / AccessDenied
  routing, the splash gate, and the real boot sequence.

The crucial correction from the last spike: in **both** options the repositories
are **real**. The `apps/integration` harness already proves this works — its
`TestClient` builds the real `RoomRepositoryImpl`, `MatchmakingRepositoryImpl`,
`NetworkClientImpl`, and reconnecting socket against the in-process server, faking
only the genuine external boundary (Supabase auth → minted test JWTs). We are not
"faking 20 dependencies." We substitute ~5 process boundaries and keep the rest
real.

## What actually has to change

### The external boundaries to substitute (both options)

These have no in-process equivalent. The DI override mechanism is already used in
this repo (`@ContributesBinding(AppScope::class, replaces = [...])` — e.g.
`DevBillingClient` replaces `NoOpBillingClient`), so swapping them is a known move.

| Boundary | Real impl | Substitute |
|---|---|---|
| Networking base URL | `DefaultNetworkConfig` (resolves dev/prod/local) | test `NetworkConfig` → in-process server's ephemeral URL |
| Auth / token | `SupabaseAuthRepositoryImpl`, `GatewayAuthTokenProvider` | the integration harness's test auth (fixed authenticated user + minted JWT) |
| Telemetry | `AppTelemetry` (Sentry) | self-disables with no DSN; or no-op override |
| Billing | already `DevBillingClient` → `FakeBillingClient` in debug | nothing — already faked |
| Shake sensor (Android) | `AndroidShakeDetector` (SensorManager) | Robolectric shadow (inert) or no-op override |

Everything else — rooms, matchmaking, gameplay, profile, progression, products,
config, in-app messages, the ViewModels, the use cases — runs real.

### Option 2 specifics

- Add a Robolectric + `compose.uiTest` test source set (`apps/compose`
  `androidUnitTest`, mirroring `apps/integration`).
- Build the real repos by hand the way `TestClient` already does (or pull a
  partial DI component), and hand each real feature entry point its
  VM factory. **This is the catch:** entry points are DI-injected with heavy VM
  dependency graphs (the play screen's VM takes ~15 collaborators). Wiring one or
  two screens by hand is fine; wiring "any flow" converges toward needing the DI
  graph anyway — which is most of Option 3's cost.

### Option 3 specifics

- Same Robolectric + `compose.uiTest` test source set.
- A `TestAppComponent` (`@MergeComponent(AppScope::class)` in the test source set)
  that `replaces` the ~5 boundaries above and `@Provides` the in-process server
  URL. kotlin-inject-anvil assembles the rest of the real graph by convention.
- One wrinkle: the in-process server binds an **ephemeral port** chosen at
  runtime, so the test `NetworkConfig` must be constructed *after* the server
  starts — i.e. pass the URL into `TestAppComponent.create(context, baseUrl)`.
  Straightforward, just not the default shape.
- Boot is friendlier than feared. `AppViewModel` flips `isBootComplete` after a
  synchronous `appCache` read (sets `startDestination`) plus a `coroutineScope`
  that awaits config under a **`withTimeoutOrNull(8000ms)`** backstop and, for
  returning users, the first profile emission. Config has a bundled offline
  fallback + 5s network timeout, so it completes with no server. Guest-account
  creation is **non-blocking** (fire-and-forget) and boot does not depend on it.
  `startDestination` is `HomeRoute` iff `appCache.hasUserOnboarded` — a local
  flag the test sets. So: seed `hasUserOnboarded = true`, give the test auth an
  authenticated user so the profile resolves, and `App()` renders Home without an
  emulator or a real backend.
- The 11 `@AutoInit` singletons that resolve at App composition were audited:
  none block boot; the heaviest (avatar-pack prefetch) is async and non-fatal.

## What's normal for an Android codebase

The industry norm for "UI/integration tests" is **Option 3's shape**: run the
real app, swap only the network + auth boundary (MockWebServer, a test backend,
or DI-injected fakes), keep everything else real. With Hilt this is the standard
`@HiltAndroidTest` + test-module / `@BindValue` recipe; here it's the anvil
`replaces` equivalent. Traditionally these run on an emulator with Espresso /
Compose-test; increasingly teams run them on the JVM with **Robolectric** for
speed. Pure nav-graph-in-isolation suites (Option 2) are less common as a primary
strategy — codebases usually pair heavy ViewModel unit tests (which this repo
already has) with full-app UI tests, rather than testing the nav graph alone.

So Option 3 is the conventional target. The unconventional part of our situation
is only that we'd point it at an **in-process Ktor server** (which we already run)
instead of MockWebServer — a strict upgrade in fidelity, since it exercises the
real server logic too.

## Pros / cons

| | Option 2 (nav graph) | Option 3 (real App) |
|---|---|---|
| Catches the MP-leave bug class | ✅ (real entry-point lambdas) | ✅ |
| App-root behavior (tabs, dialogs, session-expired routing, splash) | ❌ | ✅ |
| Upfront cost | low-moderate, but per-screen VM wiring doesn't scale | moderate (test component + boot), then ~free per test |
| Per-test cost | grows with each new screen's wiring | flat — any flow is reachable |
| Fidelity to production | partial (no shell, no boot) | high (the actual app process) |
| Failure surface | small | larger (boot, DI graph, Robolectric shadows) |
| Matches the industry norm | no | yes |
| Robolectric required | yes | yes |
| iOS coverage | no (but nav logic is commonMain, so shared paths are covered) | no (same) |

Shared cons: Robolectric adds a dependency, some flakiness, and SDK-lag
maintenance vs. pure JVM unit tests (still far faster than an emulator). The test
component's `replaces` list needs upkeep as bindings change. Neither covers iOS
rendering — but the navigator, routes, and entry-point wiring are all
`commonMain`, so the *logic* that broke is exercised on either platform's runtime.

## What the end result looks like

An Option 3 test reads like a user story and shares the in-process server with the
API-level integration suite:

```kotlin
@RunWith(RobolectricTestRunner::class)
class MpLeaveUiTest {
  @get:Rule val compose = createComposeRule()

  @Test fun leavingAMpGame_landsOnHome_notADeadLobby() = uiTest { server ->
    // real client + real screens, seeded onboarded, pointed at the in-process server
    seedOnboarded()
    val code = server.createPrivateRoomWithStartedHand(me)
    compose.setContent { App(testAppComponent(server.baseUrl)) }

    compose.onNodeWithText("Home").assertExists()          // booted past splash
    navigateIntoMpGame(code)
    compose.onNodeWithText("Leave").performClick()
    compose.onNodeWithText("Leave table").performClick()   // confirm dialog

    // the bug: this used to strand on the dead play screen / a duplicate lobby
    compose.onNodeWithTag("home_screen").assertExists()
  }
}
```

The `testAppComponent(baseUrl)` + `uiTest { }` harness is the one-time
infrastructure; every subsequent flow test is a dozen lines.

## Recommendation

**Build Option 3, incrementally, and reuse the in-process server.** It's the
conventional target, it has the flat per-test cost, and the boot sequence is
test-friendly enough that the main unknowns are mechanical (Robolectric config +
dynamic-port wiring), not architectural. Option 2 only looks cheaper until you
want real screens, at which point its per-screen VM wiring converges on the DI
work Option 3 does once.

Phase it so the risk is front-loaded:

1. **Make-or-break spike (½–1 day).** Add Robolectric + `compose.uiTest` to an
   `apps/compose` androidUnitTest source set. Author a minimal `TestAppComponent`
   (`replaces`: NetworkConfig → in-process URL, auth → test JWT, telemetry →
   no-op), seed `hasUserOnboarded = true`, render `App()`, and assert it reaches
   **Home** past `BootLoadingScreen`. If this is green, Option 3 is a few days; if
   boot or the DI graph fight back, we learn it cheaply.
2. **Lift the server into a shared fixture.** Move `InProcessServer` (today in
   `apps/integration`) into a shared test module so both the API integration
   tests and the UI tests run against one server.
3. **Write the nav-flow tests** that would have caught the regressions: leave →
   Home, opponents-left → no duplicate lobby, deep-link join fills the lobby,
   tab-switch restores its stack, session-expired routes to the blocking screen.

**Fallback:** if Phase 1 reveals boot or anvil-override friction we can't justify,
drop to Option 2 scoped to the room/lobby/play entry points — reusing the real-VM
wiring the `apps/integration` harness already has — and accept that the app shell
stays uncovered.

Either way, the layer is worth building: it's the one gap that let two regressions
ship in a day, and it's the layer the API-level integration suite (now 40 tests)
deliberately stops short of.
