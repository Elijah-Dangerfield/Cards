package com.dangerfield.cards.libraries.rooms.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.RoomConnection
import io.ktor.client.plugins.websocket.WebSocketException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the new [ReconnectingRoomSocket] over a controlled
 * [FakeRoomSocketTransport]. Production WS plumbing (URL build,
 * Frame ↔ String mapping, real Ktor handshake exceptions) is covered
 * separately in [KtorRoomSocketTransportTest] against MockEngine.
 *
 * Coverage focus:
 *
 *  - **Handle split** — the connection / gameplayFrames / send flows
 *    each surface exactly their subset of socket events.
 *  - **Sharing** — handles for the same code share one underlying
 *    transport and one coordinator; new collectors see the replayed
 *    last [RoomConnection].
 *  - **Lifecycle** — first subscriber opens the WS; last subscriber
 *    lingers [ReconnectingRoomSocket.STOP_LINGER_MS] before close; the
 *    state evicts itself after a full linger so the cache doesn't
 *    leak; resubscribe during the linger keeps the WS open.
 *  - **Reconnect** — clean drop emits Reconnecting and reopens;
 *    handshake 4xx surfaces Closed(Rejected); 5xx retries; RoomClosed
 *    is terminal.
 *  - **Replay cleanliness** — a stale `Reconnecting` from a prior
 *    session doesn't leak into a fresh state's replay buffer.
 *  - **Send** — outbound encodes correctly, multiple frames preserve
 *    order, frames queued during a disconnect re-drain on reconnect, and
 *    a saturated 32-slot outbound buffer suspends `send()` rather than
 *    growing unbounded, then drains FIFO once a session connects.
 *
 * Uses [StandardTestDispatcher] (overrides the base
 * [UnconfinedTestDispatcher]) because the linger / backoff assertions
 * need explicit `advanceTimeBy` control — eager continuations would
 * silently advance past the boundaries we're trying to assert on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectingRoomSocketTest : CoroutineTest() {

    override val testDispatcher: TestDispatcher = StandardTestDispatcher()

    /**
     * One AppCoroutineScope per test, dispatching through the test
     * scheduler so `advanceTimeBy` controls every coroutine the socket
     * spins up (coordinator, WS loop, writer).
     */
    private val appScope: AppCoroutineScope = AppCoroutineScope(dispatchers)

    @AfterTest
    fun shutdownAppScope() {
        // The socket parks long-lived coroutines on the appScope. Cancel
        // them so the test scheduler doesn't see leaks at exit.
        appScope.coroutineContext[Job]?.cancelChildren()
    }

    // ===================================================================
    // Handle split — connection vs gameplayFrames vs send
    // ===================================================================

    @Test
    fun connection_emits_Connecting_thenConnected_onSnapshot() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()

        socket.connect("ABC123").connection.test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            session.receive(RoomSocketEventDto.Snapshot(sampleRoomDto("ABC123")))
            val connected = assertIs<RoomConnection.Connected>(awaitItem())
            assertEquals("ABC123", connected.room.code)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun connection_doesNotEmit_onGameplayFrames() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()

        socket.connect("ABC123").connection.test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            session.receive(RoomSocketEventDto.Snapshot(sampleRoomDto("ABC123")))
            assertIs<RoomConnection.Connected>(awaitItem())

            session.receive(RoomSocketEventDto.GameStateSnapshot(sampleGameState()))
            session.receive(
                RoomSocketEventDto.IntentAck(clientNonce = "n1", accepted = true, error = null),
            )
            // Let the production reader process them; assert no
            // connection emission was synthesized in response.
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun gameplayFrames_doesNotEmit_onLobbyFrames() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        handle.gameplayFrames.test {
            // Force the WS to open via a second subscriber on connection.
            val connJob = launch { handle.connection.collect { } }
            advanceUntilIdle()

            session.receive(RoomSocketEventDto.Snapshot(sampleRoomDto("ABC123")))
            session.receive(RoomSocketEventDto.MemberJoined(sampleMemberDto("alice")))
            session.receive(RoomSocketEventDto.MemberLeft("bob"))
            session.receive(
                RoomSocketEventDto.MemberPresenceChanged(userId = "alice", isConnected = false),
            )
            advanceUntilIdle()
            expectNoEvents()
            connJob.cancel()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun gameplayFrames_emitsStateSnapshot_event_andIntentAck() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        handle.gameplayFrames.test {
            val connJob = launch { handle.connection.collect { } }
            advanceUntilIdle()

            session.receive(RoomSocketEventDto.GameStateSnapshot(sampleGameState()))
            assertIs<GameplayFrame.StateSnapshot>(awaitItem())

            session.receive(
                RoomSocketEventDto.GameEventOccurred(seq = 7L, event = sampleHandStarted()),
            )
            val event = assertIs<GameplayFrame.Event>(awaitItem())
            assertEquals(7L, event.seq)

            session.receive(
                RoomSocketEventDto.IntentAck(clientNonce = "n1", accepted = false, error = "not-your-turn"),
            )
            val ack = assertIs<GameplayFrame.IntentAck>(awaitItem())
            assertEquals("n1", ack.clientNonce)
            assertEquals(false, ack.accepted)
            assertEquals("not-your-turn", ack.error)

            connJob.cancel()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ===================================================================
    // Sharing across handles
    // ===================================================================

    @Test
    fun twoHandles_sameCode_shareOneTransport() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeSuccess()
        val handleA = socket.connect("ABC123")
        val handleB = socket.connect("ABC123")

        val jobA = launch { handleA.connection.collect { } }
        val jobB = launch { handleB.connection.collect { } }
        advanceUntilIdle()

        assertEquals(1, transport.openCalls, "two handles, same code → one transport")
        jobA.cancel(); jobB.cancel()
    }

    @Test
    fun twoCodes_useTwoTransports() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeSuccess()
        transport.primeSuccess()

        val jobA = launch { socket.connect("AAAAAA").connection.collect { } }
        val jobB = launch { socket.connect("BBBBBB").connection.collect { } }
        advanceUntilIdle()

        assertEquals(2, transport.openCalls)
        assertEquals(listOf("AAAAAA", "BBBBBB"), transport.openCodes)

        jobA.cancel(); jobB.cancel()
    }

    @Test
    fun connection_replayReachesLateCollector() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        val firstJob = launch { handle.connection.collect { } }
        advanceUntilIdle()
        session.receive(RoomSocketEventDto.Snapshot(sampleRoomDto("ABC123")))
        advanceUntilIdle()

        // Late collector sees the replayed Connected immediately.
        val late = handle.connection.first()
        val connected = assertIs<RoomConnection.Connected>(late)
        assertEquals("ABC123", connected.room.code)

        firstJob.cancel()
    }

    @Test
    fun cancelOneCollector_keepsTransportOpen_whileOtherStillCollects() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        val first = launch { handle.connection.collect { } }
        val second = launch { handle.gameplayFrames.collect { } }
        advanceUntilIdle()
        assertEquals(false, session.closed)

        // Drop one collector; the other keeps the WS alive past linger.
        first.cancel()
        first.join()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(false, session.closed, "second collector keeps WS alive")

        second.cancel()
    }

    // ===================================================================
    // Send
    // ===================================================================

    @Test
    fun send_encodesAndShipsFrame_overTransport() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        val connJob = launch { handle.connection.collect { } }
        advanceUntilIdle()

        handle.send(ClientFrame.StartHand(clientNonce = "n1"))
        advanceUntilIdle()

        assertEquals(1, session.sent.size)
        val encoded = session.sent.single()
        assertTrue(encoded.contains("start_hand"), "expected wire tag start_hand; got $encoded")
        assertTrue(encoded.contains("\"n1\""), "expected nonce n1; got $encoded")
        connJob.cancel()
    }

    @Test
    fun send_multipleFrames_arriveInOrder() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        val connJob = launch { handle.connection.collect { } }
        advanceUntilIdle()

        handle.send(ClientFrame.StartHand("n1"))
        handle.send(ClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = 0), "n2"))
        handle.send(ClientFrame.RequestNextHand("n3"))
        advanceUntilIdle()

        assertEquals(3, session.sent.size)
        assertTrue(session.sent[0].contains("start_hand"))
        assertTrue(session.sent[1].contains("submit_intent"))
        assertTrue(session.sent[2].contains("request_next_hand"))
        connJob.cancel()
    }

    @Test
    fun send_acrossReconnect_resendsBufferedFrames() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val first = transport.primeSuccess()
        val second = transport.primeSuccess()
        val handle = socket.connect("ABC123")

        val connJob = launch { handle.connection.collect { } }
        advanceUntilIdle()

        // Drop the WS; the writer dies with the connection. Buffer a
        // frame during the gap; it must surface on the next session.
        first.closeFromPeer()
        runCurrent()
        handle.send(ClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = 0), "after-drop"))
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, transport.openCalls, "reconnect must open a fresh session")
        assertTrue(
            second.sent.any { it.contains("after-drop") },
            "buffered frame must re-send on the new session; got: ${second.sent}",
        )
        connJob.cancel()
    }

    @Test
    fun send_saturatesOutboundBuffer_thenSuspends() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val handle = socket.connect("SAT123")

        // No collector → no live session → nothing drains the outbound
        // buffer. The first 32 frames fit the bounded buffer; the 33rd
        // must suspend rather than silently growing an unbounded queue.
        val buffered = (0 until 32).map { i ->
            launch { handle.send(ClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = 0), "n$i")) }
        }
        runCurrent()
        assertTrue(buffered.all { it.isCompleted }, "first 32 sends should fit the buffer")

        val overflow = launch {
            handle.send(ClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = 0), "overflow"))
        }
        // runCurrent (not advanceUntilIdle) so the no-subscriber linger
        // timer doesn't fire and evict the state out from under us.
        runCurrent()
        assertTrue(overflow.isActive, "33rd send must suspend on the full buffer")

        overflow.cancel()
        buffered.forEach { it.cancel() }
    }

    @Test
    fun send_saturatedBuffer_drainsFifo_andResumesSuspendedSend_onConnect() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()
        val handle = socket.connect("SAT123")

        // Saturate the buffer (33 frames; the last parks on the full
        // buffer) with no subscriber draining.
        val sends = (0..32).map { i ->
            launch { handle.send(ClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = 0), "f$i")) }
        }
        runCurrent()
        assertTrue(sends.any { it.isActive }, "one send should be parked on the full buffer")

        // A subscriber opens the WS; the writer drains FIFO, freeing the
        // slot the parked send is waiting on so it completes too.
        val connJob = launch { handle.connection.collect { } }
        advanceUntilIdle()

        assertTrue(sends.all { it.isCompleted }, "draining must resume the parked send")
        assertEquals(33, session.sent.size, "every buffered frame drains on connect")
        assertTrue(session.sent.first().contains("\"f0\""), "FIFO: f0 ships first; got ${session.sent.first()}")
        assertTrue(session.sent.last().contains("\"f32\""), "FIFO: parked f32 ships last; got ${session.sent.last()}")
        connJob.cancel()
    }

    // ===================================================================
    // Reconnect + errors
    // ===================================================================

    @Test
    fun handshakeFailure_emitsConnecting_thenReconnecting() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeFailure(RuntimeException("boom"))

        socket.connect("ABC123").connection.test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val reconnecting = assertIs<RoomConnection.Reconnecting>(awaitItem())
            assertEquals(1, reconnecting.attempt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handshake4xx_webSocketException_byMessage_surfaces_Closed_Rejected() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        // Ktor's WebSocketException carries the status in its message;
        // classifyHandshake parses it via a regex.
        transport.primeFailure(
            WebSocketException("Handshake exception, expected status code 101 but was 403"),
        )

        socket.connect("ABC123").connection.test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val closed = assertIs<RoomConnection.Closed>(awaitItem())
            assertEquals(ClosedReason.Rejected, closed.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handshake5xx_webSocketException_byMessage_surfaces_Reconnecting() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeFailure(
            WebSocketException("Handshake exception, expected status code 101 but was 503"),
        )

        socket.connect("ABC123").connection.test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val reconnecting = assertIs<RoomConnection.Reconnecting>(awaitItem())
            assertEquals(1, reconnecting.attempt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun roomClosedFrame_emits_Closed_RoomDeleted_andStopsLoop() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()

        socket.connect("ABC123").connection.test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            session.receive(RoomSocketEventDto.RoomClosed)
            val closed = assertIs<RoomConnection.Closed>(awaitItem())
            assertEquals(ClosedReason.RoomDeleted, closed.reason)
            // After RoomClosed the loop returns; advancing time must NOT
            // produce another open attempt.
            advanceTimeBy(60_000)
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, transport.openCalls, "loop must not reopen after RoomClosed")
    }

    @Test
    fun cleanDrop_withoutRoomClosed_emitsReconnecting_andReopens() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val first = transport.primeSuccess()
        transport.primeSuccess()
        val handle = socket.connect("ABC123")

        val seen = mutableListOf<RoomConnection>()
        val job = launch { handle.connection.collect { seen += it } }
        advanceUntilIdle()
        first.closeFromPeer()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, transport.openCalls, "clean drop must trigger one reopen")
        val reconnecting = seen.filterIsInstance<RoomConnection.Reconnecting>().firstOrNull()
        assertNotNull(reconnecting, "should have emitted Reconnecting; saw $seen")
        assertEquals(1, reconnecting.attempt)
        job.cancel()
    }

    @Test
    fun consecutiveFailures_incrementAttemptCounter() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeFailure(RuntimeException("nope-1"))
        transport.primeFailure(RuntimeException("nope-2"))

        val attempts = mutableListOf<Int>()
        val job = launch {
            socket.connect("ABC123").connection.collect { conn ->
                if (conn is RoomConnection.Reconnecting) attempts += conn.attempt
            }
        }
        // Plenty of virtual time for both backoffs.
        advanceTimeBy(60_000)
        runCurrent()
        job.cancel()

        assertEquals(listOf(1, 2), attempts.take(2))
    }

    // ===================================================================
    // Lifecycle: subscribe count, linger, eviction
    // ===================================================================

    @Test
    fun firstSubscriber_opensTransport() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeSuccess()

        val job = launch { socket.connect("ABC123").connection.collect { } }
        advanceUntilIdle()

        assertEquals(1, transport.openCalls)
        job.cancel()
    }

    @Test
    fun noSubscribers_doesNotOpenTransport() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        // Just call connect — no one subscribes.
        socket.connect("ABC123")
        advanceUntilIdle()

        assertEquals(0, transport.openCalls, "connect alone must not open the WS")
    }

    @Test
    fun lastSubscriberCancels_lingers_thenClosesTransport() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()

        val job = launch { socket.connect("ABC123").connection.collect { } }
        advanceUntilIdle()
        assertEquals(false, session.closed)

        job.cancel()
        job.join()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(false, session.closed, "transport must linger before close")

        advanceTimeBy(600)
        runCurrent()
        assertEquals(true, session.closed, "transport closes after linger expires")
    }

    @Test
    fun resubscribeDuringLinger_keepsTransportOpen() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val session = transport.primeSuccess()

        val first = launch { socket.connect("ABC123").connection.collect { } }
        advanceUntilIdle()
        first.cancel()
        first.join()

        // Mid-linger resubscribe.
        advanceTimeBy(500)
        val second = launch { socket.connect("ABC123").connection.collect { } }
        runCurrent()

        // Way past the original linger deadline.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(false, session.closed, "resubscribe must keep WS alive")
        assertEquals(1, transport.openCalls, "no second open")
        second.cancel()
    }

    @Test
    fun afterLingerExpires_stateEvicts_nextConnect_buildsFreshTransport() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        val first = transport.primeSuccess()
        transport.primeSuccess()

        val firstJob = launch { socket.connect("ABC123").connection.collect { } }
        advanceUntilIdle()
        firstJob.cancel()
        firstJob.join()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(true, first.closed)

        val secondJob = launch { socket.connect("ABC123").connection.collect { } }
        advanceUntilIdle()

        assertEquals(2, transport.openCalls, "post-eviction connect must open fresh")
        secondJob.cancel()
    }

    @Test
    fun replayCache_doesNotLeakStaleReconnecting_acrossEviction() = runUnitTest {
        val transport = FakeRoomSocketTransport()
        val socket = newSocket(transport)
        transport.primeFailure(RuntimeException("first fails"))
        val session = transport.primeSuccess()
        transport.primeSuccess()

        // Drive a Reconnecting into the replay buffer, then a success
        // snapshot, then cancel and wait for eviction.
        val seen = mutableListOf<RoomConnection>()
        val job = launch { socket.connect("ABC123").connection.collect { seen += it } }
        advanceTimeBy(60_000); runCurrent()
        session.receive(RoomSocketEventDto.Snapshot(sampleRoomDto("ABC123")))
        advanceUntilIdle()
        assertTrue(seen.any { it is RoomConnection.Reconnecting })
        job.cancel(); job.join()
        advanceTimeBy(2_000); runCurrent()

        // Fresh state for the same code — replay must be clean.
        val seenSecond = mutableListOf<RoomConnection>()
        val freshJob = launch {
            socket.connect("ABC123").connection.collect { seenSecond += it }
        }
        advanceUntilIdle()
        assertTrue(
            seenSecond.firstOrNull() == RoomConnection.Connecting,
            "fresh state must start clean; saw $seenSecond",
        )
        assertTrue(
            seenSecond.none { it is RoomConnection.Reconnecting && it.cause?.message == "first fails" },
            "stale Reconnecting must not survive eviction; saw $seenSecond",
        )
        freshJob.cancel()
    }

    // ===================================================================
    // Scaffolding
    // ===================================================================

    private fun newSocket(transport: FakeRoomSocketTransport): ReconnectingRoomSocket =
        ReconnectingRoomSocket(transport = transport, appScope = appScope)

    private fun sampleRoomDto(code: String) = RoomDto(
        code = code,
        hostUserId = "host-user",
        createdAtEpochMs = 0L,
        maxSeats = 6,
        status = RoomStatusDto.Lobby,
        members = listOf(sampleMemberDto("host-user")),
    )

    private fun sampleMemberDto(userId: String) = RoomMemberDto(
        userId = userId,
        displayName = "Alice",
        seatIndex = 0,
        joinedAtEpochMs = 0L,
        isConnected = true,
    )

    private fun sampleGameState(): GameState = GameState(
        settings = RoomSettings.Default,
        handNumber = 1,
        buttonSeatIndex = 0,
        seats = emptyList(),
        community = emptyList(),
        street = BettingRound.Preflop,
        currentBetThisStreet = 0L,
        lastFullRaiseSize = 0L,
        actingSeatIndex = null,
        deckRemaining = emptyList(),
    )

    private fun sampleHandStarted(): GameEvent =
        GameEvent.HandStarted(sequence = 1L, handNumber = 1, buttonSeatIndex = 0)
}
