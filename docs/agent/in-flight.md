## refactor(shake): route ShakeHandler through DispatcherProvider.main

**Problem:** `ShakeHandler` constructed its `CoroutineScope` with raw `Dispatchers.Main`, violating the repo's dispatcher-injection rule (production code consumes `DispatcherProvider.*` so tests can swap a `TestDispatcher`).
**Approach:** Added `dispatchers: DispatcherProvider` to the `@Inject` constructor and routed the scope through `dispatchers.main`. `apps:compose` already depends on `:libraries:flowroutines`.
**Reviewer notes:** No new test — the todo entry explicitly noted this dispatcher swap doesn't change observable behavior and no test sibling exists.

## test(level): pin XP curve + LevelProgress derived props

**Problem:** `Level.kt` (quadratic XP curve, `levelProgressFor` resolver, `LevelProgress` derived fraction with `coerceIn(0f, 1f)` + divide-by-zero guard) is consumed by Home / Stats / Profile / Shop / Room VMs but had no test pin; the `MAX_LEVEL=100` clamp, the negative-XP clamp, and the fraction-fallback were all unverified.
**Approach:** Added `LevelTest` covering the curve at known levels, the `<1` clamp on `xpToLevelUpFrom`, the level-from-XP boundaries (0, 99, 100, negative, beyond MAX_LEVEL), the three derived `LevelProgress` properties, plus a monotonicity sweep. New `commonTest.dependencies` block added to `:libraries:cards` (it had none) wired to `:libraries:flowroutines:testing` for source-set parity with the rest of the module graph; the tests themselves use plain `kotlin.test` because `Level.kt` is pure math.
**Reviewer notes:** None.
