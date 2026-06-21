# In-flight (this cycle)

## feat: thread server-tunable level curve through display sites

**Problem:** Display sites derived level off the bundled `DefaultLevelCurve` (`levelProgressFor(xp)`, no curve arg), so a server-retuned `progression.levelCurve` would make the shown level disagree with the granted one.

**Approach:** Added a `LocalLevelCurve` `staticCompositionLocalOf` in `:libraries:ui` (default `DefaultLevelCurve` so previews/tests render without a provider) and provide it once at the `App.kt` root from `ProgressionConfig.levelCurve()`, recomputed when app-config rolls in. Composable display sites (`StatsScreen`, `ProfileScreen`, `QaMenuScreen`, DS `LevelPill` xp-overload) read the local; the curve-owning ViewModels (`HomeViewModel`, `ShopViewModel`, `EditProfileViewModel`) pass `progressionConfig.levelCurve()` to `levelProgressFor`. Chose the composition-local for leaf composables (todo's stated direction) over DI-per-leaf, and one-time-per-config-change read over a per-frame flow since the curve is a rare server retune.

**Reviewer notes:** Sliced — the multiplayer-table seat/opponent level projection (`PlayPokerViewModel` / `RemotePokerSessionFactory.occupantsFor` / `TableUiState` badge) is *not* in this commit: it needs the curve threaded through the `PokerSessionFactory` projection interface, which is load-bearing MP code I kept out of a P2 display fix. `docs/todo.md` was rewritten to scope that remainder + the part-(b) server reconcile. Added a `ShopViewModelTest` case proving a steep configured curve keeps 100 XP at level 1 where the default curve shows level 2. Preview-only `levelProgressFor` call sites (`HomeScreen`, `HomeHeader`) left on the default — sample data, exempt.

## test: cover late-mount gameplay replay at the VM level (B6)

**Problem:** B6's subscribe-after-action gap — tests subscribed before the action, the app subscribes after — left the gameplay flow without VM-level late-subscriber coverage, and `FakeRoomConnectionHandle` couldn't catch the regression because it flattened the whole gameplay flow into one `replay=0` SharedFlow.

**Approach:** Made `FakeRoomConnectionHandle.gameplayFrames` faithful to production (`ReconnectingRoomSocket`): the latest `StateSnapshot` rides a replay-1 `MutableStateFlow` merged with the replay-0 events `SharedFlow`, so a late subscriber replays the live table while mid-hand joiners don't re-fire stale event animations. Added a reusable `startHandBeforeJoinerMounts()` harness scenario and a VM-level test asserting a play screen mounting *after* the deal renders the table instead of sitting on Loading.

**Reviewer notes:** Test-only, no production change. The fake-fidelity fix touches the shared `FakeRoomConnectionHandle` used by both `RemotePokerSessionTest` and the integration suite; ran the full `:features:room:impl` test suite green to confirm the StateFlow-conflation semantics don't break existing before-subscribe tests. The socket-level replay test (`gameplayState_replayReachesLateCollector`) already existed — that part of the item's premise was stale; this adds the missing VM-level guarantee + the reusable harness. The todo was re-scoped to the remaining "audit other hot flows" sweep rather than deleted.
