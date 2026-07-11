# ENG-20: `runWhen` — level-based sync triggers

**Scope (owner-confirmed): phases 1 + 2 in this change** — the trigger machinery rebuild
AND migrating the two hand-rolled triad consumers, ending with `AppEvent.AccountClaimed`
deleted end to end.

## Context

The owner's 07-09 prod session never uploaded its pending chip grants because the sync
coordinator is edge-triggered off a shared event bus with a single replay slot: a later boot
event can evict the sign-in event before the coordinator subscribes, and a sync blocked on
"auth not ready yet" is never retried. Investigation (case file
`docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`) ranked five ways a trigger
gets lost. The agreed direction from design discussion: repositories should wait on
**conditions (levels)**, with a small set of **edges** that re-fire while the condition holds —
`runWhen(condition, refireOn) { work }`.

Key insight from design review: the bug is *many concerns multiplexed through one replay-1
slot*, not "edges are bad". Auth's own flow (`MutableSharedFlow(replay=1)` holding only
`AuthState`) is already level-correct for late subscribers. So **auth stays a SharedFlow** —
no `AuthState.Resolving` sentinel, no contract changes to `current()`/`observe()`. For the
trigger key, "unresolved" and "unauthenticated" are both just `null`.

## Architecture (Option A — keep `Set<UserScopedSyncer>`, rebuild the coordinator)

- The 6 repos (Chips/Progression/Equipment/PlayerStats/Inventory/Achievement) **do not change**.
  They keep `@ContributesBinding(..., multibinding = true, boundType = UserScopedSyncer::class)`
  and `suspend fun sync(): Result<Unit>`. The "when" policy stays in exactly one file.
- Rejected: per-repo `runWhen` in `init` (6 copies + 6 new AutoInit bindings; forgetting one is
  a silent no-sync bug) and a declarative per-contributor spec (all 6 want the identical spec;
  mechanical evolution later if a 7th diverges).
- Upgrade inside A: the coordinator starts **one `runWhen` per syncer** — independent
  single-flight, retry, and cancellation. A failing wallet sync no longer re-runs progression.

## New API

### 1. `libraries/flowroutines/src/commonMain/.../RunWhen.kt` (new, generic, zero app deps)

```kotlin
fun <K : Any> CoroutineScope.runWhen(
    key: Flow<K?>,                      // null = off; non-null = on; key CHANGE = cancel + refire
    refireOn: Flow<*> = emptyFlow(),    // edges that re-fire while key is non-null
    retry: RunWhenRetry = RunWhenRetry.None,
    work: suspend (K) -> Result<Unit>,
): Job

fun CoroutineScope.runWhen(condition: Flow<Boolean>, ...)   // Unit-key convenience

class RunWhenRetry(val maxRetries: Int, val delayFor: (attempt: Int) -> Duration)
// RunWhenRetry.None; RunWhenRetry.exponential(initial=5s, factor=2.0, max=2m, retries=5)
```

Semantics (each pinned by a test):
- Fires when key becomes non-null **including already-non-null-at-subscribe** (kills the boot race).
- Key change (user switch, anon→claimed `isAnonymous` flip) cancels in-flight work and fires fresh.
- Key → null cancels everything including pending retry backoff.
- Single-flight with trailing coalesce: `collectLatest` + conflated trigger channel + serial loop.
- Failed run retries on the schedule while the key holds; bounded (next edge re-arms anyway).
- `work` throwing = failed attempt (wrapped in `Catching`), retryable; crash-isolation preserved.

### 2. `libraries/cards/impl/.../SyncTriggers.kt` (new — app-state wiring)

```kotlin
data class ActiveAccount(val userId: String, val isAnonymous: Boolean)

class SyncTriggers(authRepository, appEvents, appState) {
  val activeAccount: Flow<ActiveAccount?>   // from authRepository.observe(); data-class equality IS the refire contract
  val warmForeground: Flow<Unit>            // AppEvent.OnForeground(isColdBoot=false)
  val cameOnline: Flow<Unit>                // from AppState.isOffline LEVEL: drop(1) + debounce(750ms) + filter(!it)
}
```

- Deriving `activeAccount` from `observe()` preserves the load-bearing ordering invariant:
  `SupabaseAuthRepositoryImpl.emitLocked` awaits `userScopedDataReset.clearFor(previous)`
  **before** emitting — a new user's sync can never start before the old user's data clear.
- `cameOnline` derives from the `isOffline` level, NOT the replayed `ConnectivityRegained`
  event (a replayed edge could spuriously refire at boot). Transiently duplicates
  `ConnectivityEdgeDispatcher`'s pipeline until phase 3 deletes the dispatcher — accepted.
- Rejected: a combined `AppStateSnapshot` StateFlow. Keys must stay minimal — connectivity is
  a refire edge, not a gate (going offline mid-sync must NOT cancel the sync).

### 3. `UserScopedSyncCoordinator.kt` (rewrite internals; same DI shape, stays AutoInit)

```kotlin
init {
    syncers.forEach { syncer ->
        appScope.runWhen(
            key = triggers.activeAccount,
            refireOn = merge(triggers.warmForeground, triggers.cameOnline),
            retry = RunWhenRetry.exponential(),
        ) { syncer.sync() }
    }
}
```

Deleted: the `when(event)` block, `activeUserId` state machine, `AccountClaimed` arm, `syncAll`.

## Decisions made (documented here so review can veto)

- Retry: tiny local `RunWhenRetry`, NOT networking's `RetryPolicy` (dependency direction; wrong
  layer; the two retries coexist — `sync()` keeps its inner `RetryPolicy.idempotent()` for
  network blips, `runWhen` covers long-horizon failures like the auth gate opening late).
- Backoff numbers: 5s → x2 → 2m cap, 5 retries. Retry exhaustion logs a warning (KLog) in the
  coordinator; no new telemetry hook.
- Naming: `runWhen` as a `CoroutineScope` extension (reads at call site; noun-shaped
  alternatives push toward registration objects nobody needs).
- Turbine: not introduced; fakes + `CoroutineTest` virtual time match the existing suite.

## Behavior deltas to state in the PR

Syncers now also refire on reconnect; failed syncs retry with backoff; concurrent triggers
coalesce instead of stacking; in-flight syncs are cancelled on sign-out/user-switch (today
they run to completion); claim refires without the `AccountClaimed` event.

## Files

| File | Change |
|---|---|
| `libraries/flowroutines/src/commonMain/kotlin/com/cards/libraries/flowroutines/RunWhen.kt` | new — primitive + `RunWhenRetry` |
| `libraries/flowroutines/src/commonTest/kotlin/.../RunWhenTest.kt` | new (module may need a commonTest source set; use bare `runTest` if depending on `flowroutines/testing` creates a cycle) |
| `libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/SyncTriggers.kt` | new — `ActiveAccount` + three trigger flows |
| `libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/UserScopedSyncCoordinator.kt` | rewrite internals |
| `libraries/cards/impl/src/commonTest/kotlin/.../UserScopedSyncCoordinatorTest.kt` | rewrite (see test list) |
| `docs/todo.md` | update ENG-20 to point at this plan; retire on completion |
| `docs/decisions.md` | new entry: level-based triggers, Option A, auth stays SharedFlow |

### Phase 2 files (same change, after phase 1 lands green)

| File | Change |
|---|---|
| `libraries/cards/impl/.../PlayStyleRepositoryImpl.kt` | its hand-rolled sync triad (`onUserChanged`/`onAccountClaimed`/`onForeground`, ~lines 154–175) becomes a `UserScopedSyncer` contribution; the synchronous opponent-cache clear on user change STAYS a small `AppEventListener` (moment side-effect — retrying it would be wrong) |
| `libraries/cards/impl/.../InAppMessageManagerImpl.kt` | message-sync path moves to the level/edge world (own `runWhen` on `activeAccount` + warm-fg/came-online, or a `UserScopedSyncer` contribution — worker's call, state it in the PR); the drop-dialog-on-user-change side effect STAYS a listener |
| `libraries/cards/src/.../AppEvent.kt` | delete `AccountClaimed` |
| `libraries/cards/.../AppEventListener.kt` + `AppEventDispatcher.kt` | delete `onAccountClaimed` method + dispatch arms |
| `libraries/identity/impl/.../SupabaseAuthRepositoryImpl.kt` | delete the `AccountClaimed` dispatch site in `emitLocked` (~line 321); the clear-before-emit ordering MUST stay byte-identical otherwise |
| affected tests | `AppEventDispatcherTest`, PlayStyle/InAppMessage tests: drop AccountClaimed cases, add claim-via-key-flip coverage |

Unchanged: the 6 phase-1 repos, `ConnectivityEdgeDispatcher` (phase 3), all other listeners,
bus replay config (phase 3).

## Test list

**RunWhenTest (primitive, virtual time):**
1. key already non-null at subscribe → fires exactly once (the boot-race regression, primitive level)
2. null→non-null fires once; stays-null never fires
3. key A→B cancels in-flight A, fires B
4. key→null mid-run cancels; mid-backoff cancels (advance time, assert zero further attempts)
5. refire while non-null runs again; while null does nothing
6. three refires during a slow run → exactly 2 total runs (trailing coalesce)
7. failure → attempts at t=0/+5s/+10s (pin schedule); success stops; exhaustion stops; next refire re-arms fresh counter
8. boolean overload: true-at-subscribe fires; repeated `true` doesn't refire
9. work throwing ≠ scope death; counts as failed attempt

**UserScopedSyncCoordinatorTest (wiring, real SyncTriggers over fakes — fake AuthRepository
(SharedFlow replay=1), fake AppEvents, fake AppState(MutableStateFlow)):**
1. **prod-incident regression:** auth resolves before coordinator constructs + boot events already fired → each syncer synced exactly once
2. cold boot with restored session (events then resolve) → one sync each
3. fresh install unauthenticated + boot + warm fg → zero syncs; later guest-heal flips auth → fires once
4. sign-in fires; sign-out mid-backoff stops retries
5. user switch: in-flight A cancelled; B starts only after new AuthState emission (assert clear→emit ordering)
6. claim (same id, isAnonymous flip) refires — with NO AccountClaimed on the fake bus
7. warm fg refires only while authed; cold fg never double-fires
8. isOffline true→false past 750ms virtual debounce refires; flap inside window → once
9. one failing syncer retries alone; other five run once
10. slow syncer + foreground spam → one trailing run

## Verification

- `./gradlew :libraries:flowroutines:testDebugUnitTest :libraries:cards:impl:testDebugUnitTest`
  (all new tests + existing `*SyncTest` suite green).
- Compile all targets: `:libraries:cards:impl:compileKotlinIosSimulatorArm64`,
  `:apps:compose:compileDebugKotlinAndroid`, `:apps:server:compileKotlin`.
- Manual smoke (simulator, dev backend): earn an achievement offline-ish, kill the app,
  relaunch with the restored session → outbox flushes on launch without a sign-in (the
  original incident's repro, now the trigger half only — balance-display fold is PROG-11).

## Follow-ups (filed, not in this change)

- **Phase 3 (optional):** drop bus replay 1→0 once `OfflineFirstAppConfigRepository` is checked;
  migrate identity's condition-shaped listeners (GuestSessionHealer etc.) and delete
  `ConnectivityEdgeDispatcher` + `ConnectivityRegained`.
- **Pre-existing clear-window hazard** (old user's in-flight sync can still be running during
  `clearFor` — narrowed by cancellation-on-switch but not closed): file as its own todo.
- PROG-11 (balance fold + rejected-event surfacing) remains separate and unblocked by this.
