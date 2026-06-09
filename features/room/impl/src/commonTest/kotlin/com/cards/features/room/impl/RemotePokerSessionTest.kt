package com.dangerfield.cards.features.room.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [RemotePokerSession] over a [FakeRoomConnectionHandle]. The
 * socket layer (`:libraries:rooms:impl`) is already tested in
 * isolation; here we focus on:
 *
 *  - The session's three flow projections: [GameState],
 *    [GameEvent], and [ConnectionState] each derive correctly from
 *    inbound gameplay frames / connection transitions.
 *  - `submit(intent)` mints a nonce, sends a [ClientFrame.SubmitIntent],
 *    suspends on the matching [GameplayFrame.IntentAck], and surfaces
 *    accept / reject / timeout cleanly. The pending-ack map cleans up
 *    in every branch so the session doesn't leak per-intent state.
 *  - `requestNextHand` is fire-and-forget and conflates rapid taps
 *    into a single outbound frame.
 *
 * Uses [StandardTestDispatcher] so timeout assertions are
 * deterministic under virtual time.
 */
class RemotePokerSessionTest : CoroutineTest() {

    override val testDispatcher: TestDispatcher = StandardTestDispatcher()

    // ===================================================================
    // gameStateFlow / events / connectionState
    // ===================================================================

    @Test
    fun gameStateFlow_initially_holdsSentinelEmptyState() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)

        val initial = session.gameStateFlow.value
        assertTrue(initial.seats.isEmpty(), "pre-snapshot state must be empty so the factory can render Loading")
        assertEquals(0, initial.handNumber)
    }

    @Test
    fun gameStateFlow_replacesValueOnStateSnapshot() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        val server = sampleGameStateWithSeats(handNumber = 1)
        handle.pushFrame(GameplayFrame.StateSnapshot(server))
        advanceUntilIdle()

        assertEquals(server, session.gameStateFlow.value)
        runJob.cancel()
    }

    @Test
    fun gameStateFlow_dropsSnapshotArrivingOutOfOrder_withinSameHand() = runUnitTest {
        // The transport doesn't guarantee snapshot order beyond the
        // engine's sequence numbers; an older snapshot landing after a
        // newer one (e.g. a buffered frame from a dropped connection)
        // must not clobber the live table.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        val newer = sampleGameStateWithSeats(handNumber = 1).copy(lastSequence = 10L)
        handle.pushFrame(GameplayFrame.StateSnapshot(newer))
        advanceUntilIdle()
        assertEquals(newer, session.gameStateFlow.value)

        val stale = sampleGameStateWithSeats(handNumber = 1).copy(lastSequence = 4L, actingSeatIndex = 1)
        handle.pushFrame(GameplayFrame.StateSnapshot(stale))
        advanceUntilIdle()

        assertEquals(newer, session.gameStateFlow.value, "a lower-sequence snapshot must not overwrite the live state")
        runJob.cancel()
    }

    @Test
    fun gameStateFlow_appliesNewHand_evenThoughItsSequenceResets() = runUnitTest {
        // lastSequence resets to 0 at the start of each hand, so the new
        // hand's opening snapshot carries a *lower* sequence than the prior
        // hand's final one. handNumber is the cross-hand ordering key — the
        // new hand must apply despite the sequence dropping.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        val handOneFinal = sampleGameStateWithSeats(handNumber = 1).copy(lastSequence = 42L)
        handle.pushFrame(GameplayFrame.StateSnapshot(handOneFinal))
        advanceUntilIdle()
        assertEquals(handOneFinal, session.gameStateFlow.value)

        val handTwoOpen = sampleGameStateWithSeats(handNumber = 2).copy(lastSequence = 3L)
        handle.pushFrame(GameplayFrame.StateSnapshot(handTwoOpen))
        advanceUntilIdle()

        assertEquals(handTwoOpen, session.gameStateFlow.value, "a fresh hand must apply even though its sequence reset")
        runJob.cancel()
    }

    @Test
    fun gameStateFlow_appliesEqualSequenceResync() = runUnitTest {
        // A post-reconnect resync may re-send the same (handNumber, seq)
        // with refreshed content; an equal key isn't strictly older, so it
        // still applies — dropping it would swallow a legitimate re-send.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        val first = sampleGameStateWithSeats(handNumber = 1).copy(lastSequence = 7L, actingSeatIndex = 0)
        handle.pushFrame(GameplayFrame.StateSnapshot(first))
        advanceUntilIdle()

        val resync = sampleGameStateWithSeats(handNumber = 1).copy(lastSequence = 7L, actingSeatIndex = 1)
        handle.pushFrame(GameplayFrame.StateSnapshot(resync))
        advanceUntilIdle()

        assertEquals(resync, session.gameStateFlow.value, "equal-sequence resync must apply, not be dropped as stale")
        runJob.cancel()
    }

    @Test
    fun events_replays_recentEventsToLateSubscriber() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        // Push a HandStarted event BEFORE anyone subscribes — replay=16
        // means a late subscriber still sees it.
        handle.pushFrame(GameplayFrame.Event(seq = 1L, event = sampleHandStarted()))
        advanceUntilIdle()

        val first = session.events.replayCache.firstOrNull()
        assertIs<GameEvent.HandStarted>(first)
        runJob.cancel()
    }

    @Test
    fun events_doesNotEmit_onStateSnapshotOrIntentAck() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        session.events.test {
            handle.pushFrame(GameplayFrame.StateSnapshot(sampleGameStateWithSeats(1)))
            handle.pushFrame(
                GameplayFrame.IntentAck(clientNonce = "x", accepted = true, error = null),
            )
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runJob.cancel()
    }

    @Test
    fun connectionState_mirrorsRoomConnectionTransitions() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)

        assertEquals(ConnectionState.Disconnected, session.connectionState.value)

        val runJob = launch { session.run() }
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Connecting)
        advanceUntilIdle()
        assertEquals(ConnectionState.Reconnecting, session.connectionState.value)

        handle.pushConnection(RoomConnection.Connected(sampleRoom()))
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, session.connectionState.value)

        handle.pushConnection(RoomConnection.Reconnecting(attempt = 1, cause = null))
        advanceUntilIdle()
        assertEquals(ConnectionState.Reconnecting, session.connectionState.value)

        handle.pushConnection(RoomConnection.Closed(ClosedReason.RoomDeleted))
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, session.connectionState.value)

        runJob.cancel()
    }

    // ===================================================================
    // roomClosed — terminal-close fan-out
    // ===================================================================

    @Test
    fun roomClosed_emits_onTerminalRoomDeleted() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val closes = mutableListOf<ClosedReason>()
        val runJob = launch { session.run() }
        val collectJob = launch { session.roomClosed.collect { closes += it } }
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Closed(ClosedReason.RoomDeleted))
        advanceUntilIdle()

        assertEquals(listOf(ClosedReason.RoomDeleted), closes)
        // Still collapses to Disconnected for the banner.
        assertEquals(ConnectionState.Disconnected, session.connectionState.value)
        runJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun roomClosed_emits_onRejected() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val closes = mutableListOf<ClosedReason>()
        val runJob = launch { session.run() }
        val collectJob = launch { session.roomClosed.collect { closes += it } }
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Closed(ClosedReason.Rejected))
        advanceUntilIdle()

        assertEquals(listOf(ClosedReason.Rejected), closes)
        runJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun roomClosed_doesNotEmit_onCancelled() = runUnitTest {
        // Cancelled is our own teardown — the user is already leaving, so
        // popping the screen again would be a spurious double-navigation.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val closes = mutableListOf<ClosedReason>()
        val runJob = launch { session.run() }
        val collectJob = launch { session.roomClosed.collect { closes += it } }
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Closed(ClosedReason.Cancelled))
        advanceUntilIdle()

        assertTrue(closes.isEmpty(), "Cancelled is self-initiated; must not fan out a terminal close")
        runJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun roomClosed_doesNotEmit_onTransientReconnecting() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val closes = mutableListOf<ClosedReason>()
        val runJob = launch { session.run() }
        val collectJob = launch { session.roomClosed.collect { closes += it } }
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Connecting)
        handle.pushConnection(RoomConnection.Reconnecting(attempt = 2, cause = null))
        advanceUntilIdle()

        assertTrue(closes.isEmpty(), "a transient drop is recoverable; terminal-close must not fire")
        runJob.cancel()
        collectJob.cancel()
    }

    // ===================================================================
    // submit() — accept, reject, timeout, cleanup
    // ===================================================================

    @Test
    fun submit_sendsSubmitIntentFrame_andCompletes_onAccepted() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        runCurrent()

        // Capture submit's outcome via runCatching so an exception
        // from the async body doesn't propagate to the test scope
        // before assertFailsWith gets a chance to catch it.
        var outcome: Result<Unit>? = null
        val submitJob = launch {
            outcome = runCatching { session.submit(PlayerIntent.Fold(seatIndex = 0)) }
        }
        runCurrent()

        val outbound = handle.sent.single()
        val submitFrame = assertIs<ClientFrame.SubmitIntent>(outbound)
        handle.pushFrame(
            GameplayFrame.IntentAck(
                clientNonce = submitFrame.clientNonce,
                accepted = true,
                error = null,
            ),
        )
        runCurrent()
        submitJob.join()

        assertTrue(outcome?.isSuccess == true, "expected accepted; got $outcome")
        runJob.cancel()
    }

    @Test
    fun submit_throwsIntentRejected_onAckRejection() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        runCurrent()

        var outcome: Result<Unit>? = null
        val submitJob = launch {
            outcome = runCatching { session.submit(PlayerIntent.Fold(seatIndex = 0)) }
        }
        runCurrent()
        val submitFrame = handle.sent.single() as ClientFrame.SubmitIntent
        handle.pushFrame(
            GameplayFrame.IntentAck(
                clientNonce = submitFrame.clientNonce,
                accepted = false,
                error = "not-your-turn",
            ),
        )
        runCurrent()
        submitJob.join()

        val ex = outcome?.exceptionOrNull()
        assertIs<IntentRejectedException>(ex)
        assertEquals("not-your-turn", ex.reason)
        runJob.cancel()
    }

    @Test
    fun submit_throwsIntentTimeout_whenNoAckArrives() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        runCurrent()

        var outcome: Result<Unit>? = null
        val submitJob = launch {
            outcome = runCatching { session.submit(PlayerIntent.Fold(seatIndex = 0)) }
        }
        runCurrent()
        advanceTimeBy(RemotePokerSession.INTENT_TIMEOUT_MS + 500)
        runCurrent()
        submitJob.join()

        assertIs<IntentTimeoutException>(outcome?.exceptionOrNull())
        runJob.cancel()
    }

    @Test
    fun submit_correctsNonceCorrelation_whenIntermediateAckHasDifferentNonce() = runUnitTest {
        // Two concurrent submits race; the session must resolve each
        // deferred against its own nonce, not the most-recent one.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        runCurrent()

        var firstResult: Result<Unit>? = null
        val firstJob = launch {
            firstResult = runCatching { session.submit(PlayerIntent.Fold(seatIndex = 0)) }
        }
        runCurrent()
        var secondResult: Result<Unit>? = null
        val secondJob = launch {
            secondResult = runCatching { session.submit(PlayerIntent.Check(seatIndex = 1)) }
        }
        runCurrent()

        val frames = handle.sent.map { it as ClientFrame.SubmitIntent }
        assertEquals(2, frames.size)

        // Ack the SECOND one first; the first must stay pending.
        handle.pushFrame(
            GameplayFrame.IntentAck(
                clientNonce = frames[1].clientNonce,
                accepted = true,
                error = null,
            ),
        )
        runCurrent()
        secondJob.join()
        assertEquals(true, secondResult?.isSuccess)
        assertEquals(false, firstJob.isCompleted, "first submit must not be resolved by the second's ack")

        // Now ack the first.
        handle.pushFrame(
            GameplayFrame.IntentAck(
                clientNonce = frames[0].clientNonce,
                accepted = true,
                error = null,
            ),
        )
        runCurrent()
        firstJob.join()
        assertEquals(true, firstResult?.isSuccess)
        runJob.cancel()
    }

    @Test
    fun submit_dropsPendingDeferred_onTimeout() = runUnitTest {
        // After a timeout, a late-arriving ack for the same nonce must
        // not crash or re-resolve a stale deferred. We assert this by
        // re-submitting and observing the next nonce works cleanly.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        runCurrent()

        var firstResult: Result<Unit>? = null
        val first = launch {
            firstResult = runCatching { session.submit(PlayerIntent.Fold(seatIndex = 0)) }
        }
        runCurrent()
        val firstNonce = (handle.sent.single() as ClientFrame.SubmitIntent).clientNonce
        advanceTimeBy(RemotePokerSession.INTENT_TIMEOUT_MS + 500)
        runCurrent()
        first.join()
        assertIs<IntentTimeoutException>(firstResult?.exceptionOrNull())

        // A late ack for the old nonce arrives — must be silently dropped.
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = firstNonce, accepted = true, error = null),
        )
        runCurrent()

        // A fresh submit still works.
        var secondResult: Result<Unit>? = null
        val second = launch {
            secondResult = runCatching { session.submit(PlayerIntent.Check(seatIndex = 0)) }
        }
        runCurrent()
        val secondNonce = (handle.sent.last() as ClientFrame.SubmitIntent).clientNonce
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = secondNonce, accepted = true, error = null),
        )
        runCurrent()
        second.join()
        assertEquals(true, secondResult?.isSuccess)
        runJob.cancel()
    }

    @Test
    fun submit_dropsPendingDeferred_onCancellation() = runUnitTest {
        // External cancellation of the submit coroutine must also clean
        // up the pending entry so the next submit's accounting is clean.
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        runCurrent()

        val first = launch { session.submit(PlayerIntent.Fold(seatIndex = 0)) }
        runCurrent()
        first.cancel()
        first.join()
        runCurrent()

        // A new submit should round-trip cleanly.
        var secondResult: Result<Unit>? = null
        val second = launch {
            secondResult = runCatching { session.submit(PlayerIntent.Check(seatIndex = 0)) }
        }
        runCurrent()
        val secondNonce = (handle.sent.last() as ClientFrame.SubmitIntent).clientNonce
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = secondNonce, accepted = true, error = null),
        )
        runCurrent()
        second.join()
        assertEquals(true, secondResult?.isSuccess)
        runJob.cancel()
    }

    // ===================================================================
    // requestNextHand
    // ===================================================================

    @Test
    fun requestNextHand_sends_RequestNextHandFrame() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        session.requestNextHand()
        advanceUntilIdle()

        val outbound = handle.sent.single()
        assertIs<ClientFrame.RequestNextHand>(outbound)
        runJob.cancel()
    }

    @Test
    fun requestNextHand_conflatesRapidTaps_intoSingleFrame() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        // Don't pump the drainer yet — fire three taps before any
        // drain so Channel.CONFLATED can collapse them.
        session.requestNextHand()
        session.requestNextHand()
        session.requestNextHand()
        runCurrent()

        assertEquals(1, handle.sent.size, "rapid taps must conflate; got: ${handle.sent}")
        runJob.cancel()
    }

    @Test
    fun requestNextHand_canFire_repeatedlyOverTime() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val session = RemotePokerSession(handle)
        val runJob = launch { session.run() }
        advanceUntilIdle()

        repeat(3) {
            session.requestNextHand()
            advanceUntilIdle()
        }

        // Three distinct taps separated by drain ticks → three frames.
        val frames = handle.sent.filterIsInstance<ClientFrame.RequestNextHand>()
        assertEquals(3, frames.size)
        // Each carries a fresh nonce (server-side dedupe relies on it).
        assertEquals(3, frames.map { it.clientNonce }.toSet().size)
        runJob.cancel()
    }

    // ===================================================================
    // Scaffolding
    // ===================================================================

    private fun sampleGameStateWithSeats(handNumber: Int): GameState = GameState(
        settings = RoomSettings.Default,
        handNumber = handNumber,
        buttonSeatIndex = 0,
        seats = listOf(
            com.dangerfield.cards.libraries.gameplay.Seat(
                index = 0,
                playerId = "local-user",
                displayName = "Local",
                stack = 100L,
                seatStatus = com.dangerfield.cards.libraries.gameplay.SeatStatus.Active,
                handParticipation = com.dangerfield.cards.libraries.gameplay.HandParticipation.InHand,
                isBot = false,
            ),
            com.dangerfield.cards.libraries.gameplay.Seat(
                index = 1,
                playerId = "peer",
                displayName = "Peer",
                stack = 100L,
                seatStatus = com.dangerfield.cards.libraries.gameplay.SeatStatus.Active,
                handParticipation = com.dangerfield.cards.libraries.gameplay.HandParticipation.InHand,
                isBot = false,
            ),
        ),
        community = emptyList(),
        street = BettingRound.Preflop,
        currentBetThisStreet = 0L,
        lastFullRaiseSize = 0L,
        actingSeatIndex = 0,
        deckRemaining = emptyList(),
    )

    private fun sampleHandStarted(): GameEvent.HandStarted =
        GameEvent.HandStarted(sequence = 1L, handNumber = 1, buttonSeatIndex = 0)

    private fun sampleRoom(): com.dangerfield.cards.libraries.rooms.Room =
        com.dangerfield.cards.libraries.rooms.Room(
            code = "ABC123",
            hostUserId = "host",
            createdAtEpochMs = 0L,
            maxSeats = 6,
            status = com.dangerfield.cards.libraries.rooms.RoomStatus.Playing,
            members = emptyList(),
        )
}

/**
 * Controllable [RoomConnectionHandle] used by the session tests. The
 * production handle is its own well-tested type ([ReconnectingRoomSocket]
 * end-to-end); the session doesn't care that it's a Ktor socket
 * underneath — only that frames flow in and `send` ships them out.
 */
internal class FakeRoomConnectionHandle : RoomConnectionHandle {
    private val _connection = MutableSharedFlow<RoomConnection>(replay = 1, extraBufferCapacity = 8)
    private val _gameplayFrames = MutableSharedFlow<GameplayFrame>(replay = 0, extraBufferCapacity = 64)

    val sent: MutableList<ClientFrame> = mutableListOf()

    override val connection: SharedFlow<RoomConnection> = _connection.asSharedFlow()
    override val gameplayFrames: SharedFlow<GameplayFrame> = _gameplayFrames.asSharedFlow()

    override suspend fun send(frame: ClientFrame) {
        sent += frame
    }

    suspend fun pushConnection(state: RoomConnection) {
        _connection.emit(state)
    }

    suspend fun pushFrame(frame: GameplayFrame) {
        _gameplayFrames.emit(frame)
    }
}
