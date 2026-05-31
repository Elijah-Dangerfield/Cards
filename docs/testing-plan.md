# Multiplayer testing plan

This is the master capture for getting multiplayer (and the gameplay engine that underpins it) to "more solid than the Brooklyn Bridge" confidence. The rest of the app can ship with normal-shape unit coverage; **multiplayer is the load-bearing feature and gets the heavy treatment.**

Append-only as ideas land. Each round below is a coherent chunk you can pick up in one session. The rounds are ordered by impact-per-hour, not strictly by dependency — you can interleave or reorder if a real bug surfaces.

## Mission

> If two humans across the country open the app, find each other, and play a full hand against each other, **nothing in our code is the reason it fails.** Network blips, server restarts, app backgrounds, host disconnects, slow networks, fast double-taps — all of it handled, or handled-then-recovered, or surfaced honestly to the user.

That means:

1. **Every public seam has tests** — the unit layer must catch regressions to the contracts.
2. **The integration layer is real** — real Ktor server in-process, real client, real wire bytes. Contract drift impossible to ship undetected.
3. **The gameplay engine is property-tested for invariants** — pot conservation, stack conservation, betting math correctness across the full action cross-product.
4. **The chaos cases are enumerated and tested** — reconnects mid-hand, host drops, intent races, out-of-order frames. Anything we've seen or can predict gets a test.
5. **UI states are covered** — the screen renders correctly for every state the VM can produce; Compose snapshot/UI tests catch the projection layer.

The only category we're consciously deferring is **device-emulator-based UI tests** (slow, flaky, high infrastructure cost) — captured in [Deferred](#deferred-with-rationale).

---

## Current coverage snapshot (as of the V1 MP-feature stack)

Honest baseline — what exists today, line-counted from the test files (Sept 2025 reading):

| Layer | Tests | Files | What's covered |
|---|---|---|---|
| **Client socket** | 62 | [`ReconnectingRoomSocketTest`](../libraries/rooms/impl/src/commonTest/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocketTest.kt), [`KtorRoomSocketTransportTest`](../libraries/rooms/impl/src/commonTest/kotlin/com/cards/libraries/rooms/impl/KtorRoomSocketTransportTest.kt), [`RoomRepositoryImplTest`](../libraries/rooms/impl/src/commonTest/kotlin/com/cards/libraries/rooms/impl/RoomRepositoryImplTest.kt) | Handle split, sharing, lifecycle, reconnect, replay, send buffering, URL build, HTTP outcome mapping. |
| **Client session** | 17 | [`RemotePokerSessionTest`](../features/room/impl/src/commonTest/kotlin/com/cards/features/room/impl/RemotePokerSessionTest.kt) | Flow projections, submit accept/reject/timeout, nonce correlation, cleanup, requestNextHand. |
| **Client lobby** | ~16 | [`LobbyViewModelTest`](../features/lobby/impl/src/commonTest/kotlin/com/cards/features/lobby/impl/LobbyViewModelTest.kt) | Idle form, create/join outcomes, leave cancellation. **Missing: every new MP path.** |
| **Client VM (bots)** | ~10 | [`PlayPokerViewModelIntegrationTest`](../features/room/impl/src/commonTest/kotlin/com/cards/features/room/impl/PlayPokerViewModelIntegrationTest.kt) | Real bot-mode session wiring. **No MP-session equivalent.** |
| **Server WS routes** | ~12 | [`RoomSocketRoutesTest`](../apps/server/src/test/kotlin/com/cards/server/routes/RoomSocketRoutesTest.kt) | Real server + real client through lobby/presence flow. **Missing: full gameplay turn cycle via the route.** |
| **Server game session** | ~15 | [`GameSessionTest`](../apps/server/src/test/kotlin/com/cards/server/game/GameSessionTest.kt) | startHand seeding, nonce dedupe, mutex guarding, intent rejection. |
| **Server registry** | exists | [`GameSessionRegistryIntegrationTest`](../apps/server/src/test/kotlin/com/cards/server/game/GameSessionRegistryIntegrationTest.kt) | Registry lifecycle. |
| **Engine (shared)** | substantial | [`GameEngineTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/GameEngineTest.kt) + [`GameEngineAdvancedTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/GameEngineAdvancedTest.kt) + [`HandEvaluatorTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/HandEvaluatorTest.kt) + [`PotBuilderTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/PotBuilderTest.kt) + [`GeneratedHandsTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/GeneratedHandsTest.kt) | Engine actions, evaluator, pot building, hand fixtures. **Missing: property tests for invariants.** |
| **End-to-end integration** | 0 | — | **Nothing exercises both real client + real server stacks together.** |

All fakes are hand-rolled (no Mockito/MockK). No mock-library smell anywhere.

---

## Round 1 — close the new MP code's silent-failure gaps

**Cost:** ~3-4 hours, ~15 tests. **Why first:** the new MP code shipped with zero coverage on its load-bearing wiring. Highest bang-for-buck in the whole plan.

### `LobbyViewModel` — new MP paths

Add to [`LobbyViewModelTest`](../features/lobby/impl/src/commonTest/kotlin/com/cards/features/lobby/impl/LobbyViewModelTest.kt):

- [x] `startGame_hostInRoomWith2Members_sendsStartHandFrame_andEmitsNavigateEvent` — assert `handle.send(ClientFrame.StartHand(...))` was called and `LobbyEvent.NavigateToMultiplayer(code)` fired.
- [x] `startGame_nonHost_isNoOp` — non-host taps Start (shouldn't normally render the button, but assertion guards against state desync); no frame sent, no event.
- [x] `startGame_hostAlone_isNoOp` — `canStart` false (members < 2); no frame, no event.
- [x] `gameplaySnapshotReceived_nonHost_emitsNavigateEvent` — fire the action; assert `NavigateToMultiplayer` event.
- [x] `gameplaySnapshotReceived_host_doesNotEmitNavigateAgain` — host already navigated on tap; second navigation would push twice in the back stack.
- [x] `gameplaySnapshotReceived_secondCall_doesNotReEmit` — `hasReceivedGameplaySnapshot` guards against re-fire on subsequent snapshots.
- [x] `effectiveHostUserId_allConnected_isFirstMember` — base case.
- [x] `effectiveHostUserId_firstMemberDisconnected_promotesNextConnected` — auto-promotion case.
- [x] `effectiveHostUserId_noConnectedMembers_isNull` — degenerate; guards the null-host branch.
- [x] `effectiveHostUserId_originalHostReconnects_returnsToOriginal` — reconnect mid-session.
- [x] `connectionUpdated_hostChanges_emitsHostPromotedEvent` — fire `ConnectionUpdated` with a snapshot where the effective host shifted; assert `HostPromoted` event with correct name + `isLocalUser` flag. **Surfaced + fixed a latent bug: the promotion check re-read the lagging derived `stateFlow` for the new host, so the banner never fired; now keyed off the applied snapshot.**
- [x] `connectionUpdated_hostUnchanged_doesNotEmitPromotion` — same host on subsequent snapshot; no event.
- [x] `connectionUpdated_initialSetup_doesNotEmitPromotion` — first Connected emission; no event (we'd false-positive on every join).

### `RemotePokerSessionFactory` — projection logic

Create [`RemotePokerSessionFactoryTest`](../features/room/impl/src/commonTest/kotlin/com/cards/features/room/impl/RemotePokerSessionFactoryTest.kt) — **this is the most critical addition** because the `humanSeatIndex` lookup keying off `localUserId` is load-bearing for every action submission:

- [x] `occupantsFor_emptyState_returnsEmptyList` — pre-snapshot case.
- [x] `occupantsFor_filledSeats_derivesHumanBotEmpty` — three-seat state with each variant; assert exact `SeatOccupant` shapes.
- [x] `occupantsFor_humanSeat_carriesPlayerIdAndDisplayName` — pin the data on the Human variant.
- [x] `tableFor_emptyState_returnsLoading` — Loading sentinel before first snapshot.
- [x] `tableFor_localUserAtSeat0_humanSeatIndexIs0` — base lookup.
- [x] `tableFor_localUserAtSeat3_humanSeatIndexIs3` — non-zero seat.
- [x] `tableFor_localUserNotInRoom_humanSeatIndexIsMinusOne` — observer/spectator case; assert it doesn't crash and renders correctly.
- [x] `tableFor_userReseats_pickedUpInNextProjection` — V1 forbids re-seating but the dynamic lookup should still work; tests the invariant.
- [x] `difficultyName_and_xpMode_areMultiplayer` — pin the labels.
- [x] `bootstrap_callsSessionRun` — verify bootstrap actually drives the session loop.

---

## Round 2 — stand up the integration / e2e module

**Cost:** ~6-8 hours, ~10 tests in addition to the module setup. **Why now:** the wire format between client and server is the highest-risk single category — every other test mocks one side. Pays back on every future change.

### Module structure

Create `:integration` (or `:tests:e2e`) as a JVM-only Kotlin module:

```
integration/
  build.gradle.kts                  # JVM-only, depends on :apps:server + :libraries:rooms:impl + :features:room:impl
  src/test/kotlin/com/cards/integration/
    helpers/
      InProcessServer.kt             # Brings up a real Ktor server via testApplication { } with a real GameSession + InMemoryRoomService
      LiveRemotePokerSession.kt      # Builds a real RemotePokerSession against a real socket pointed at the in-process server
      TestUser.kt                    # Bootstraps an anonymous Supabase identity (or stubs Auth) for one test user
    socket/
      RoomSocketContractTest.kt      # Wire-format round trips
    gameplay/
      TwoClientHandTest.kt           # Two clients play a full hand against each other
      ReconnectMidHandTest.kt
      HostDisconnectMidHandTest.kt
```

**Design notes:**
- The integration module is **JVM-only** — KMP-target tests for native/iOS don't get the in-process server. iOS-specific stuff (the actual Darwin WebSocket path) is covered separately by the existing socket unit tests + manual device QA.
- The integration module is **run in a separate Gradle source set / CI job** so it doesn't slow the per-module `:libraries:X:test` loop. Spec out a `./gradlew :integration:test` target.
- Auth is stubbed (a `FakeAuthRepository` injected into the network client). Supabase isn't part of the contract surface we're testing.
- **Server side** of the integration is a real `Application.module(...)` configured for in-memory storage (the existing `InMemoryRoomService` already exists for this). No Postgres dependency — Postgres has its own test layer.

### Initial test set

- [ ] **`fullHand_twoClients_dealBetCallFold_renderEqual`** — two clients join the same room, one taps Start, both observe HandStarted → BlindPosted → HoleCardsDealt → fold-around → HandEnded → PotAwarded; assert both clients' `GameState` matches at each step.
- [ ] **`submitIntent_wrongSeat_serverRejects_clientSurfacesIntentRejected`** — client A submits an intent for client B's seat; assert the server `IntentAck.accepted = false` and the client throws `IntentRejectedException` with the server's reason.
- [ ] **`submitIntent_outOfTurnAttempt_doesNotMutateState`** — both clients see the same state before and after the rejected intent.
- [ ] **`startHand_serverBroadcastsToBothSubscribers`** — host's StartHand reaches both connected clients via separate handles. (Catches "did sharing actually work end-to-end.")
- [ ] **`wireFormat_GameStateSnapshot_roundTripsLosslessly`** — server constructs a GameState, sends as JSON over WS, client decodes; deep-equality assert. (Pins the wire contract — any field added to one side without the other breaks this immediately.)
- [ ] **`wireFormat_allGameEvent_variants_roundTrip`** — same as above for every `GameEvent` sealed variant.
- [ ] **`wireFormat_allClientFrame_variants_roundTrip`** — client → server direction.
- [ ] **`reconnect_midHand_resyncsState`** — drop and restore one client's WS during a hand; assert their GameStateFlow re-converges to the live state.
- [ ] **`hostDisconnects_clientPromotionDetected_otherClientSeesHostBadgeMove`** — host drops; the other client's LobbyState reflects the new effective host within one snapshot.
- [ ] **`twoClientsRace_sameIntent_serverDedupesViaNonce`** — both clients send the same intent with the same nonce; server processes once; both clients see `IntentAck.accepted` (server's idempotency contract).

### What integration tests are NOT for

- Not a substitute for unit tests — they're slower and harder to debug than a focused unit test.
- Not for testing every gameplay rule — that's the engine tests' job in `:libraries:gameplay`. Integration uses the engine but doesn't re-verify it.
- Not for testing UI rendering — that's Compose UI tests (Round 4).

---

## Round 3 — gameplay engine: SUPER tested

**Cost:** ~6-8 hours, ~20-30 tests + a property-based test framework. **Why:** the engine is the brain. A subtle bug in pot math is the kind of thing that ships and then haunts a year later. It's also the easiest layer to comprehensively test because it's pure.

### Property-based tests

Landed in [`GameEnginePropertyTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/GameEnginePropertyTest.kt). Rather than add a `kotest-property` dependency (and a JVM-only test split), these run a deterministic random-legal-action driver over 300 seeds × varying seat counts / stacks / button positions in plain `kotlin.test` — multiplatform-safe, and a failure reproduces from the printed seed. Invariants pinned:

- [x] **Stack conservation** — for any sequence of legal actions, `sum(seat.stack) + chips-on-table == sum(starting stacks)` mid-hand, and `sum(seat.stack) == starting total` once Complete. No chip created or destroyed.
- [x] **Pot eligibility correctness** — pot conservation (`sum(pot.amount) == total contributed`), eligible seats are always in-hand, and side-pot eligibility nests monotonically (each pot ⊆ the prior). Exact per-tier eligibility for the 3-way all-in case is pinned by `GameEngineAdvancedTest`.
- [x] **Hand monotonicity** — `lastSequence` strictly increases across emitted events within a hand and matches the final state's `lastSequence`.
- [x] **Acting seat rotation** — `actingSeatIndex` always points to a seat with `canAct = true`, or is null exactly when the hand is Complete.
- [x] **Settlement equals winnings** — sum of `PotAwarded.amount` == sum of pot amounts before settlement == total contributed.
- [x] **Hole-card scrub correctness** — `state.scrubbedFor(viewerSeat)` (and `-1` spectator) never reveals a non-viewer seat's hole cards unless that seat is in-hand at showdown; the viewer always sees their own.

### Cross-product table tests

For every `(street, action, seatStatus, handParticipation)` combo, assert the engine's response (accept / reject / specific reason):

- [ ] **Preflop blinds posted correctly for 2..9 seats.**
- [ ] **Heads-up button = SB**, multi-way SB = first after button.
- [ ] **Action sequence preflop:** UTG → … → BB has option to raise.
- [ ] **Postflop action sequence:** SB-left first (or first remaining left of button if SB folded).
- [ ] **Raise must be at least previous raise size** — table all "min-raise" boundary cases.
- [ ] **All-in for less than min-raise** — does NOT reopen action.
- [ ] **All-in for more than min-raise** — DOES reopen action.
- [ ] **Check when bet is 0**: legal. **Check when bet > 0**: illegal.
- [ ] **Call when stack ≥ bet**: full call. **Call when stack < bet**: all-in for stack.
- [ ] **Fold any time you can act**: legal.
- [ ] **Bet ≤ stack required**: illegal otherwise.

### Edge-case scenarios

Landed in [`GameEngineEdgeCaseTest`](../libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/GameEngineEdgeCaseTest.kt) (board-plays chop and zero-stack-sat-out were already pinned by `GameEngineAdvancedTest`):

- [x] **Fold around to BB** — BB wins SB + own posted blind.
- [x] **All seats all-in preflop, run-out to showdown** — no further action; engine deals all streets in one go.
- [x] **Side pot with three different all-in stacks** — pot 1 has all three, pot 2 has two, pot 3 has remaining.
- [x] **Showdown ties on the board (board plays)** — chops the pot. (`GameEngineAdvancedTest.showdown_splitPotOnTie`.)
- [x] **Showdown three-way tie with one short-stack** — sidepot math + tie split simultaneously.
- [x] **Stack of 0 starts hand** — that seat is sat-out, not dealt. (`GameEngineAdvancedTest.startHand_skipsSeatsWithZeroStack`.)
- [x] **Single contender after folds** — engine fast-forwards to PotAwarded without dealing remaining streets.
- [x] **Hand ends mid-street on fold-around** — community cards stop being dealt.
- [x] **Bot vs human seat parity** — engine behavior identical regardless of `isBot` flag (the flag is metadata only at the engine layer).

### Hand history regression tests

- [ ] **Capture 50 real hands from a production playtest** (when we get there) and freeze them as `.json` fixtures. The test runs each fixture through the engine and asserts the events match. This catches any engine change that drifts behaviour.

---

## Round 4 — server-side gameplay flow + WS plumbing

**Cost:** ~3-4 hours, ~8-10 tests. **Why:** `GameSessionTest` covers the engine wrapper. `RoomSocketRoutesTest` covers the lobby/presence flow. Nothing covers the plumbing between them — and that's where wire-level regressions hide.

Landed in a new [`RoomSocketGameplayRoutesTest`](../apps/server/src/test/kotlin/com/cards/server/routes/RoomSocketGameplayRoutesTest.kt) (kept separate from `RoomSocketRoutesTest`'s lobby/presence focus):

- [x] **`submitIntent_validIntent_appliesToEngine_andBroadcastsGameStateSnapshot`** — full route → session → broadcast cycle.
- [x] **`submitIntent_invalidIntent_repliesIntentAckRejected_doesNotBroadcastSnapshot`** — wrong-seat / illegal action.
- [x] **`submitIntent_duplicateNonce_processedOnce_acksTwice`** — server idempotency.
- [x] **`startHand_fromNonHost_isRejected`** — host-only gating server-side (defense-in-depth even though client gates).
- [x] **`startHand_whenHandInProgress_isRejected`** — can't restart mid-hand.
- [x] **`requestNextHand_anyPlayer_advances`** — any seated player can advance.
- [x] **`gameStateSnapshot_isScrubbedPerRecipient`** — viewer doesn't see other seats' hole cards in the broadcast.
- [x] **`gameEventOccurred_carriesSequence`** — sequence numbers are monotonic per-session.
- [x] **`socketDisconnect_midHand_engineContinues`** — the engine isn't tied to a single WS connection; other players' actions still process.

---

## Round 5 — chaos / fault injection

**Cost:** ~4-6 hours, ~10 tests. **Why:** the cases users actually hit on real networks. Most of these are integration tests living in `:integration`.

- [ ] **WS drops mid-`SubmitIntent`** — outbound frame may or may not have shipped; on reconnect, server's nonce dedupe should make the resubmit a no-op if processed, or fire normally if not.
- [ ] **Server restart mid-hand** — client reconnects, server has hydrated state from snapshot store, client's first `GameStateSnapshot` matches pre-restart state.
- [ ] **Client backgrounded > 30s during opponent's turn** — on resume, client's `connection` collector reconnects, `gameplayFrames` flow re-syncs, no stale state shown.
- [ ] **Two clients race the host role (host disconnects + reconnects fast)** — should not produce two effective hosts.
- [ ] **Server sends `RoomClosed` mid-hand** — client surfaces ClosedReason.RoomDeleted; play screen exits gracefully.
- [ ] **High-latency network (200ms+ RTT)** — `submit()` ack arrives slowly; UI doesn't double-submit on accidental tap.
- [ ] **WS handshake during auth refresh** — the auth bootstrap was the source of one real iOS bug; pin the race.
- [ ] **Outbound channel saturation** — fill the 32-slot buffer; assert `send()` suspends correctly.
- [ ] **Out-of-order frames** — server (per the existing decision log) doesn't guarantee order beyond `lastSequence`; engine state always resolves correctly.
- [ ] **Concurrent intents from multiple clients on the same hand** — server's per-session mutex serializes; both clients' acks arrive in some order.

### Reconnect / resync (B5 — currently out of scope but worth a stub test)

- [ ] **Event-tail catch-up after reconnect** — when this lands (todo §B5), test that a reconnecting client requests events since `lastKnownSequence` and the server replays.

---

## Round 6 — Compose UI tests for `PlayPokerScreen`

**Cost:** ~6-8 hours, ~15 tests + setup. **Why:** the screen has 6+ distinct states (your turn, bot thinking, raise unavailable, showdown, fold-around, loading, connection lost). Each one is its own visual + interaction surface. Per [AGENTS.md](../AGENTS.md), every screen needs `@Preview` (we have those); UI tests catch the runtime behaviour the previews can't.

### Setup

- Wire `androidx.compose.ui.test` into `:features:room:impl`'s `androidUnitTest` sourceset.
- Add a `ComposeTestRule`-based test harness that renders `PlayPokerScreen` with a controlled `PlayPokerState`.

### Tests

- [ ] **`yourTurnPreflop_actionButtons_visibleAndEnabled`** — fold/call/raise buttons render, are tappable, fire the right actions.
- [ ] **`notYourTurn_actionButtons_disabled`** — buttons present but greyed; no taps fire.
- [ ] **`showdown_handResultDialog_shows_winners`** — dialog appears with the right copy.
- [ ] **`connectionLost_bannerShown`** — `ConnectionState.Disconnected` shows the banner.
- [ ] **`raiseAmount_belowMin_buttonDisabled`** — min-raise gating UI matches engine.
- [ ] **`raiseAmount_aboveStack_buttonDisabled`** — over-bet gating.
- [ ] **`opponentFolds_seatFadesOut`** — opponent fold visual.
- [ ] **`humanFolds_swipeFoldGesture_armsConfirm`** — swipe gesture interaction.
- [ ] **`achievementCelebrationSheet_singleUnlock_renders`** — single-achievement sheet appears.
- [ ] **`achievementCelebrationSheet_multipleUnlocks_scroll`** — pin the recent bug we fixed.
- [ ] **`tablePersonalitiesPanel_botMode_visible`** / **`tablePersonalitiesPanel_mpMode_hidden`** — mode-specific UI.
- [ ] **`lobbyHostBadge_travelsOnPromotion`** — promotion banner + badge re-render.

---

## Deferred (with rationale)

### Emulator-based instrumented UI tests (Espresso / KMP iOS UI)

**Why deferred:** they're 10-100× slower than Compose UI tests, require Android emulators / iOS simulators in CI (real infrastructure cost), and historically the dominant failure mode is flake. The Compose UI tests in Round 6 cover the same surface in-process.

**When to revisit:** when a user-visible behaviour ships broken to a real device that none of the in-process tests caught — that's a signal we're missing the platform-specific layer. Until then, the **device-smoke checklist** in [`developer-todo.md`](./developer-todo.md) ("device smoke test before merging dev → main") is the human substitute.

### Mutation testing

Could run something like [pitest](https://pitest.org/) to find tests that pass even when the production code is silently mutated. Genuinely useful but very high cost-to-set-up for marginal returns over a thoughtful coverage check.

**When to revisit:** after Round 1-5 are done and we want to invest in finding the *quality* of existing tests rather than adding more.

### Consumer-driven contract tests (Pact)

The integration module (Round 2) already covers the wire contract because client and server are in the same repo. Pact would matter if client and server diverged into separate repos / teams.

**When to revisit:** if the team ever splits client/server work into separate codebases.

### Performance / load tests

Server-side load testing (1000+ concurrent rooms) is genuinely useful but not a *correctness* test. Slot it in as its own batch when scale matters.

**When to revisit:** before a public launch where load is a real concern.

---

## Fake quality — improvements

Hand-rolled fakes are the convention. Specific issues found in audit:

- [ ] **`FakeRoomConnectionHandle` doesn't model `replay = 1` on `connection`.** A test for "late subscriber sees the last connection state" passes against the fake but the production handle has different semantics. Fix: back the fake with a real `MutableSharedFlow(replay = 1)` to match production.
- [ ] **`FakeRoomSocketTransport` doesn't simulate close → reopen on a single session.** Production WS can close and the next `open()` returns a fresh session. The fake handles this but the test contract isn't explicit. Add a `simulateReconnect()` helper.
- [ ] **No `FakeRoomServer` for the integration tier.** Round 2 standardizes this — a small `FakeRoomServer` class that responds to `StartHand` / `SubmitIntent` / `RequestNextHand` using a real `GameSession` would let client-side tests cover full turn cycles without standing up Ktor. Two layers: integration uses real Ktor + `FakeRoomServer` is for unit-fast turn-cycle tests.

---

## Patterns / conventions

For new tests landing under this plan:

- **Hand-rolled fakes only.** No Mockito/MockK. Per project convention. Fakes go in `commonTest` next to their consumer, or in a shared `:libraries:X:testing` module if reused across feature modules.
- **`StandardTestDispatcher` for time-sensitive tests** (timeouts, lingers, backoff). **`UnconfinedTestDispatcher`** (the `CoroutineTest` default) for everything else.
- **`runCurrent()` not `advanceUntilIdle()`** when there's an unwanted future-scheduled task (e.g., a `withTimeout`); `advanceUntilIdle()` fires it.
- **`runCatching { suspend body }` not `assertFailsWith { ... suspend body }`.** The latter has subtle suspend-context issues with async-thrown exceptions reaching the test scope before assertions run. Capture via `runCatching` and assert on the `Result`.
- **Property tests** belong in `:libraries:gameplay`'s commonTest (engine). Don't add property-test deps to feature modules.
- **Integration tests** belong in `:integration`. Don't try to inline a real Ktor server into a feature-module test.
- **Every test file gets a top-level KDoc** explaining what's covered + what's intentionally NOT covered (e.g., "wire format pinned in `RoomSocketContractTest`; this file only covers state-machine logic").

---

## Tracking

Each item in this doc is a checkbox. When you finish a round, leave the checkboxes ticked — this becomes the running history.

Round 0 status (everything that exists today): see [Current coverage snapshot](#current-coverage-snapshot-as-of-the-v1-mp-feature-stack).

| Round | Status | Notes |
|---|---|---|
| 0 — baseline | shipped | The MP-feature stack ([`cea38b18`](https://github.com/Elijah-Dangerfield/Cards/commit/cea38b18) through [`a59ea74d`](https://github.com/Elijah-Dangerfield/Cards/commit/a59ea74d)) shipped with the coverage in the snapshot. Round 1 closes its gaps. |
| 1 — close MP gaps | shipped | `RemotePokerSessionFactoryTest` (10) + `LobbyViewModelTest` new MP paths (13). Also caught + fixed a latent `HostPromoted` non-firing bug. |
| 2 — integration module | not started | Biggest contract-safety win. Module setup + ~10 tests, ~6-8h. |
| 3 — engine SUPER tests | in progress | Property-based invariants shipped (`GameEnginePropertyTest`, 6 invariants × 300 seeds). Cross-product table tests, edge-case scenarios, and hand-history fixtures still open. |
| 4 — server gameplay flow | shipped | `RoomSocketGameplayRoutesTest` — 9 tests pinning the WS route → registry → per-recipient broadcast cycle. |
| 5 — chaos / fault injection | not started | Lives in `:integration`. ~10 tests, ~4-6h. |
| 6 — Compose UI tests | not started | `:features:room:impl` androidUnitTest. ~15 tests, ~6-8h. |
| Deferred — emulator UI | not planned | Device-smoke checklist covers this for V1. |
