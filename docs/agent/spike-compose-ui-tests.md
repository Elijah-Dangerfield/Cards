# Spike: JVM Compose UI tests for the nav layer

**Date:** 2026-06-24
**Author:** spike driven from MP-leave bug investigation
**Status:** done; decision pending owner approval
**Artifact:** `libraries/navigation/impl/src/commonTest/.../RouterPopBackToSpikeTest.kt` (compiles, currently `@Ignore`d pending Robolectric)

## Why this spike

The two MP-leave bugs we shipped on 2026-06-24 ([c7fb2767](commit:c7fb2767), [7c70cca2](commit:7c70cca2)) lived in the layer between `PlayPokerViewModel` and the Compose Navigator — specifically in `popBackTo(LobbyRoute(), inclusive=true)` silently no-opping because route-instance equality doesn't match `LobbyRoute(autoCreate=true)`.

We have rich tests above (VM scenarios via `MpScenarioBuilder`) and below (real-server end-to-end via `apps/integration`). Neither catches navigator behaviour. The question: can we add a JVM-only Compose UI test layer cheaply, or does it require an emulator?

## What I tried

Built `RouterPopBackToSpikeTest` in `libraries/navigation/impl`:

```kotlin
@Test
fun popBackToByClass_popsLobbyRouteWithArgs_regardlessOfArgs() = runComposeUiTest {
    val router = newRouter() // real DelegatingRouter with stubbed deps
    var capturedController: NavHostController? = null
    setContent {
        val controller = rememberNavController()
        capturedController = controller
        LaunchedEffect(controller) { router.setNavController(controller, lifecycle) }
        NavHost(navController = controller, startDestination = HomeRoute()) {
            composable<HomeRoute> { Text("home") }
            composable<LobbyRoute> { Text("lobby") }
            composable<PlayRoute> { Text("play") }
        }
    }
    router.navigate(LobbyRoute(autoCreate = true))
    router.navigate(PlayRoute(roomCode = "ABCDEF"))
    router.popBackTo(LobbyRoute::class, inclusive = true)
    // assert currentBackStackEntry has HomeRoute
}
```

This is exactly the test that would have failed against the bug we fixed today: the instance form `popBackTo(LobbyRoute(), inclusive=true)` would have left `PlayRoute` on top; the class form pops correctly.

## What works

- **Dependency resolves cleanly.** Added `compose.uiTest` + `compose.material3` to `commonTest` deps. No version juggling, no transitive conflicts.
- **`DelegatingRouter` is constructable from a test.** Took four small stubs (`WebLinkLauncher`, `AuthGateChecker`, `DispatcherProvider`, `AppCoroutineScope`). All four are interfaces or trivial wrappers — same as production but with Unconfined dispatchers and no-op implementations. ~30 lines.
- **Real `NavHostController` from `rememberNavController()`** works in `setContent`. Real `NavHost` with typed `composable<RouteClass>` destinations works.
- **Route subclasses for tests work** as long as they're `open class` (matches the production `Route` superclass shape).
- **The test compiles and configures** on `:libraries:navigation:impl:compileDebugUnitTestKotlinAndroid`.

## What blocks

The test fails at runtime with:

```
java.lang.NullPointerException:
  Cannot invoke "String.toLowerCase(java.util.Locale)" because "android.os.Build.FINGERPRINT" is null
    at androidx.compose.ui.test.RobolectricIdlingStrategy_androidKt.getHasRobolectricFingerprint(RobolectricIdlingStrategy.android.kt:32)
    at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invokeSuspend(ComposeUiTest.android.kt:662)
```

`runComposeUiTest` on the **Android target** reaches into the Android runtime to decide whether Robolectric is providing the idling strategy. Without Robolectric, `android.os.Build.FINGERPRINT` is null (no Android runtime stubs in a vanilla JUnit unit test), and the NPE kills the test before `setContent` even runs.

This isn't a project-specific problem — it's how Compose UI tests work on Android targets. The fix is one of:

1. **Adopt Robolectric.** Add `org.robolectric:robolectric:4.x` to androidUnitTest deps, annotate UI-test classes with `@RunWith(RobolectricTestRunner::class)`. Standard, well-supported. Robolectric provides Android runtime stubs (including `Build.FINGERPRINT`) so the JVM unit test pretends to be Android.
2. **Switch to a JVM Desktop target.** Compose Multiplatform's `jvm("desktop")` target runs `runComposeUiTest` natively without Android stubs. Cleaner conceptually but the project uses the Android target almost exclusively; adding desktop just for tests means a parallel build pipeline.
3. **Instrumented tests on a real emulator.** What Android apps traditionally do. Slow, flaky, Android-only, expensive in CI minutes.

## Cost / benefit

Option 1 (Robolectric) is the realistic choice. Cost:

- **Build:** one new dep, ~30 MB transitive. Annotate UI-test classes with `@RunWith(RobolectricTestRunner::class)` (some boilerplate).
- **CI:** Robolectric tests are slower than pure JVM unit tests (first-test warmup ~5-10s for the simulated Android boot), but each subsequent test runs fast. Still seconds, not minutes.
- **Maintenance:** Robolectric occasionally lags Android SDK / Compose versions; usually 1-2 month catch-up window. Not zero, not high.
- **Footprint:** affects only modules with UI tests. Existing tests untouched.

Benefit:

- **The bug class we shipped today becomes mechanically catchable.** Every `popBackTo` / `navigate` / `popUpTo` call in an entry point gets covered.
- **Reusable for every nav/composable test going forward.** The four-stub harness in this spike is the entire setup — adding the next 10 tests is just writing scenarios.
- **No emulator dependency.** Runs in the same `testDebugUnitTest` task as everything else; same dev loop, same CI.
- **Covers Android AND iOS by proxy.** The `DelegatingRouter`, `Route` serialization, and `popBackTo` matching all live in `commonMain` — same code paths run on iOS. A bug that fails this JVM test fails on iOS too.

## What we'd cover, concretely

Going from this harness, the high-leverage tests (estimated half-day to write the first 10 once Robolectric is in):

| # | Test | What it pins | Would have caught |
|---|---|---|---|
| 1 | popBackTo(KClass) matches arg-loaded routes | route-class matching | Bug 1 today (A stranded after Leave) |
| 2 | popBackTo(KClass, inclusive=false) lands on the existing matching route | doesn't push a duplicate | Bug 2 today (B duplicate Lobby) |
| 3 | router.batch ordering: pop+navigate is atomic | scope-death races during teardown | the "dead table" sub-class |
| 4 | navigate(TabRoute) compile-time error stays effective | accidental TabRoute via navigate() | regression catcher |
| 5 | A blocking-error route disables all navigate calls below it | error-route gate | the "stranded on error" class |
| 6 | OpponentsLeft handler ends with [Home, Lobby] not [Home, Lobby, Lobby] | OpponentsLeft routing intent | Bug 2 specifically |
| 7 | Leave from play screen pops past Lobby to Home | onBack intent | Bug 1 specifically |
| 8 | RoomClosed event pops the play screen exactly once | replay vs. terminal handling | a class of "double-pop" surprises |
| 9 | Deep-link join: prefilledCode flows from route arg into VM | route-arg propagation | "code didn't fill the lobby" class |
| 10 | Tab switch saves & restores per-tab back stacks | tab persistence | the "tab forgets where I was" class |

## Recommendation

**Adopt Robolectric and standardise on this harness for nav/composable tests.** The cost is one dep + one test-class annotation. The benefit is catching a bug class that's already cost us two regressions in one day and would otherwise keep landing.

Concrete next steps if approved:
1. Add `robolectric` to `gradle/libs.versions.toml` + the navigation:impl + features/*/impl `androidUnitTest` deps via a build-logic helper.
2. Drop the `@Ignore` from `RouterPopBackToSpikeTest`, add `@RunWith(RobolectricTestRunner::class)`, confirm it passes.
3. Promote the four-stub `newRouter()` helper into a shared `:libraries:navigation:impl:testFixtures` (or similar) so feature modules don't re-implement it.
4. Write the 10 scenarios from the table above.

If we *don't* adopt Robolectric: the alternative is "every nav-layer bug we ship next gets caught by hand-testing on a simulator" which is what we already do, plus the `apps/integration` suite covers the layer below this one (so we'd still be making progress on the bigger picture).

I'd take the Robolectric pill. It's a known-cost dependency that unblocks a known-value test layer. The two bugs from today are not isolated — they're representative of a class we'll keep paying for until this layer exists.
