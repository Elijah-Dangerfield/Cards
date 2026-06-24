# Multiplayer testing approach

Multiplayer is the load-bearing feature; it gets the heavy treatment. The rest of the app ships with normal-shape unit coverage. This doc is a reference for how we test MP and what's still open — not a roadmap.

## Mission

If two humans across the country open the app, find each other, and play a full hand against each other, **nothing in our code is the reason it fails.** Network blips, server restarts, app backgrounds, host disconnects, slow networks, fast double-taps — all of it handled, or handled-then-recovered, or surfaced honestly to the user.

How we get there:

1. Every public seam has unit tests.
2. The integration layer (`:apps:integration`) is real — real Ktor server in-process, real client, real wire bytes. Contract drift can't ship undetected.
3. The gameplay engine is property-tested for invariants (chip / pot conservation, betting math, hole-card scrub correctness) across the full action cross-product.
4. Chaos cases are enumerated and tested — reconnects mid-hand, host drops, intent races, out-of-order frames.
5. Compose UI tests cover the screen states the VM can produce.

The only category we're consciously deferring is **device-emulator-based UI tests** — see [Deferred](#deferred).

---

## ⭐ The principle: test the seams in production order

Four real bugs surfaced during the first dev playtests (2026-06-18). **The engine caught none of them because none were engine bugs** — they all lived at a **seam** where the tests reached it in the *wrong order*.

| Bug | Seam | Why the tests missed it |
|---|---|---|
| Non-host stuck on "dealing in" | client socket (`gameplayFrames` was `replay = 0`) | Tests attached both clients' gameplay collectors **before** `startHand`. The app subscribes **after** (navigate-on-deal), so the late-subscriber drop never happened in tests. |
| Opponent avatar = grey initials | wire (avatar never serialized onto the seat) | No test asserted a public seat field reaches an **opponent's** view. |
| Winner never shown at showdown | VM projection (table re-projected only on snapshots, not on the `HandEnded` event) | `handEnded` was tested for XP/achievement side effects, never for **rendering the winner**; and never with the snapshot arriving before the event. |
| (Near-miss) showdown "hang" | test **helper** (`advancePassivelyTo` seat-dedup stalled across a street) | A flaky helper looked like an engine bug. Always confirm a failing gameplay test isn't the driver before blaming the engine. |

> The recurring trap: tests subscribe/assert **before** the action; the real app does it **after**. That single mismatch hid three of the four bugs.

Concrete rules when adding MP tests:

1. **Subscribe-after-action.** For anything a late subscriber depends on (gameplay frames, the play screen mounting on deal), attach the collector **after** the triggering action and assert it still converges.
2. **Replay symmetry.** Every hot `SharedFlow` / `StateFlow` a late subscriber reads needs a "replay reaches a late collector" test.
3. **Order-independence at projection seams.** Where the VM merges two independent flows (e.g. `GameStateSnapshot` and `HandEnded`), test **both arrival orders**.
4. **Public field reaches the opponent.** For every field that should cross the wire, assert it on the **other** player's view, not the sender's.
5. **Confirm the driver before blaming the engine.** If a wired gameplay test hangs, rewrite the driver to be cursor-based — the engine is property-tested, so a hang is usually the harness.

The engine is solid. **New testing effort should target the wire and the VM projection, in production order** — not re-test the engine.

---

## Where each layer lives

- **Engine** (`:libraries:gameplay`) — `GameEngineTest`, `GameEngineAdvancedTest`, `GameEnginePropertyTest`, `GameEngineEdgeCaseTest`, `GameEngineActionTableTest`, `HandEvaluatorTest`, `PotBuilderTest`, `GeneratedHandsTest`. Property invariants, cross-product action tables, hand evaluation. No engine fakes; tests run the real engine.
- **Client socket** (`:libraries:rooms:impl`) — `ReconnectingRoomSocketTest`, `KtorRoomSocketTransportTest`, `RoomRepositoryImplTest`, `RoomSocketWireFormatTest`. Handle split, reconnect, replay, send buffering, wire round-trips.
- **Client session** (`:features:room:impl`) — `RemotePokerSessionTest`, `RemotePokerSessionFactoryTest`. Flow projections, intent ack/timeout, nonce correlation.
- **Client VM** (`:features:room:impl`) — `PlayPokerViewModelTest`, `PlayPokerViewModelIntegrationTest`, `PlayPokerViewModelMultiplayerIntegrationTest`. Both bot mode and MP mode.
- **Server WS** (`:apps:server`) — `RoomSocketRoutesTest` (lobby / presence), `RoomSocketGameplayRoutesTest` (gameplay route → registry → broadcast).
- **Server game session** (`:apps:server`) — `GameSessionTest`, `GameSessionRegistryIntegrationTest`, `SessionHydrationTest`.
- **Full-stack** (`:apps:server`) — `FullStackRoomTest` boots the real `ServerComponent` over real Postgres (Testcontainers).
- **End-to-end integration** (`:apps:integration`) — Android library, runs as Android unit tests on the host JVM. Real in-process Ktor + two real clients with real `LobbyViewModel`s + real `RemotePokerSession`s + real socket. `FaultInjectingTransport` for drop / block. Files include `FriendsGameHappyPathTest`, `SetupJourneyTest`, `WireFormatContractTest`, `InHandPlayTest`, `DeeperPlayTest`, `WireBettingLinesTest`, `WireAllInTest`, `WireSidePotTest`, `MultiHandWireTest`, `ChaosPlayTest`, `MatchmakingPlayTest`, `NonceRaceTest`, `ConcurrentIntentTest`, `InFlightSubmitReconnectTest`, plus the 2026-06-24 lifecycle pass: `MidHandJoinPlayTest`, `ReaperGraceExpiryPlayTest`, `ForfeitAndRemovePlayerTest`, `ChipEconomyPlayTest`, `HandEndTransitionsTest`, `LobbyLifecycleTest`, `ErrorSurfacesTest`, `ConcurrencyTest`, `WireAndSnapshotTest`, `DisconnectResyncTest`, `BotIntegrationTest`, `EdgeCasesPlayTest`, `MatchmakingGapsTest`. Harness seams: injectable `reaperGrace`, per-server wallet/escrow fakes + `walletBalance`/`roomExists` probes, the real `RoomTeardownCoordinator`, and `awaitRoom`/`awaitUntil`/`startHandAwaitingAck`/`driveToCompletion`/`seatPrivate` helpers. Run via `./gradlew :apps:integration:testDebugUnitTest`.

Hand-rolled fakes only — no Mockito / MockK anywhere.

---

## Which layer catches which bug (don't duplicate)

The pyramid only stays clean if **each bug class is tested at exactly one layer — the cheapest layer that can fail when it breaks.** When you're tempted to assert the same thing at two layers, the lower one wins and the higher one doesn't get written. The recurring smell is re-verifying gameplay/escrow correctness up at the UI, or re-verifying pure VM derivations down at the integration tier.

| Bug class | Owning layer | NOT here |
|---|---|---|
| Poker rules, pot/chip math, hand eval, scrub | engine (unit, property) | never re-tested above |
| `GameState → TableUiState` derivation, action→state | VM-contract unit | not integration, not UI |
| Two-client races, escrow ledger, reconnect, host-migration, mid-hand join, lifecycle | `:apps:integration` (real client↔server) | not UI — a UI test is single-user and can't race two clients |
| Button→action wiring, back-stack/nav landing, screen-state rendering, loading/empty/error/not-found surfaces | Compose UI (see below) | not integration — don't replay gameplay through the UI |

**The integration tier is the load-bearing middle and nothing replaces it.** The Compose UI tier goes *on top* and is additive — it catches the wiring/navigation class that no other layer reaches (the 2026-06-24 MP-leave bugs lived exactly there). It does **not** re-prove escrow, payout, who-gets-dealt, or chip conservation — those are owned by `:apps:integration` + the engine. A UI test for "you won and your balance went up" asserts the **screen reflects** the new balance; the *ledger correctness* is `ChipEconomyPlayTest`'s job. Over time the UI tier should let us *thin* the VM-contract tier's "VM forwards a server event" tests (the integration tier already proves those against a real server); keep the VM tier for pure-derivation logic.

---

## Still open

These are the remaining gaps. Tracked in `docs/todo.md` under `MP-2`; this list is the worklist.

- **Compose UI / navigation tier.** Scoped + sequenced in [`docs/agent/compose-ui-testing-spike.md`](../agent/compose-ui-testing-spike.md). Two shapes were evaluated: full real `App()` via a test DI component (Option 3, the recommended target) vs. the nav graph in isolation (Option 2, the fallback). Both need Robolectric + `androidx.compose.ui.test` (no emulator) and run against the in-process server — they do **not** re-test escrow/gameplay (see *Which layer catches which bug*). Build risk is front-loaded into a make-or-break Phase 1 harness spike. Coverage is wiring + screen states only: create/join/find flows landing on the right screen, not-found / no-game-found / bad-code surfaces, leave→Home, host-leaves-private routing, and the `PlayPokerScreen` states (your turn, bot thinking, raise unavailable, showdown, fold-around, loading, connection lost). Cap it at ~15-20 tests; it's a thin top, not a second integration suite.
- **Server restart mid-hand → full client reconnect.** Server-side hydration is pinned by `SessionHydrationTest`. The open part is the client-reconnect-after-restart end-to-end in `:apps:integration`.
- **`FakeRoomServer` for the integration tier.** A fake that responds to `StartHand` / `SubmitIntent` / `RequestNextHand` using a real `GameSession`, so client-side tests can cover full turn cycles without booting Ktor. Two layers: real Ktor for end-to-end, `FakeRoomServer` for unit-fast turn cycles.
- **Hand-history regression fixtures.** Capture 50 real hands from a playtest, freeze as `.json`, replay through the engine on every change. Gated on a real production playtest.
- **Event-tail catch-up after reconnect** — if/when a rolling-event-tail ships, test that a reconnecting client requests events since `lastKnownSequence` and the server replays.

Cross-cutting: any new playtest bug should add a production-order regression test the same day. See [The principle](#-the-principle-test-the-seams-in-production-order).

---

## Conventions

For new tests landing in any of the layers above:

- **Hand-rolled fakes only.** No Mockito / MockK — project convention. Fakes go in `commonTest` next to their consumer, or in a shared `:libraries:X:testing` module if reused.
- **`StandardTestDispatcher` for time-sensitive tests** (timeouts, lingers, backoff). **`UnconfinedTestDispatcher`** (the `CoroutineTest` default) for everything else.
- **`runCurrent()` not `advanceUntilIdle()`** when there's an unwanted future-scheduled task (e.g. a `withTimeout`); `advanceUntilIdle()` fires it.
- **`runCatching { suspend body }` not `assertFailsWith { … suspend body }`.** The latter has subtle suspend-context issues with async-thrown exceptions reaching the test scope before assertions run.
- **Property tests** belong in `:libraries:gameplay`'s commonTest. Don't add property-test deps to feature modules.
- **Integration tests** belong in `:apps:integration`. Don't try to inline a real Ktor server into a feature-module test.
- **Every test file gets a top-level KDoc** explaining what's covered + what's intentionally NOT covered (e.g. "wire format pinned in `RoomSocketWireFormatTest`; this file only covers state-machine logic").
- **Integration tests aren't a substitute for unit tests.** They're slower, harder to debug. Use them for the seam contract (real wire, real plumbing), not for every gameplay rule — that's the engine's job.

---

## Deferred

- **Emulator-based instrumented UI tests** (Espresso / KMP iOS UI). 10-100× slower than Compose UI tests, real infrastructure cost in CI, flake-prone. The device-smoke checklist in `docs/developer-todo.md` is the human substitute. Revisit if a user-visible behaviour ships broken on a real device that none of the in-process tests caught.
- **Mutation testing** (pitest). Useful for assessing test *quality* but very high setup cost for marginal returns. Revisit once the open items above ship.
- **Consumer-driven contract tests** (Pact). Client and server share a repo — the integration module already pins the wire contract. Revisit only if the team ever splits client/server into separate codebases.
- **Performance / load tests.** Not a correctness test. Slot in before any public launch where load matters.
