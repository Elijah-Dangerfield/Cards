package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.DefaultGameSessionRegistry
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.game.NoOpSessionSnapshotStore
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.plugins.installWebSockets
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * End-to-end tests for the **gameplay** leg of the per-room WebSocket —
 * Round 4 of [docs/testing-plan.md]. [RoomSocketRoutesTest] covers the
 * lobby / presence / reconnect flow; [GameSessionTest] +
 * [com.cards.server.game.GameSessionRegistryIntegrationTest] cover the
 * session/registry in isolation. Nothing exercised the plumbing
 * *between* the WS route and the registry — the wire-level decode →
 * dispatch → per-recipient broadcast cycle — which is where wire
 * regressions hide.
 *
 * Each test brings up a real Ktor server with a real
 * [DefaultGameSessionRegistry] and drives it through real client
 * sockets sending real [RoomClientFrame] bytes.
 *
 * What we pin:
 *  - `StartHand` from the host applies to the engine + broadcasts a
 *    `GameStateSnapshot`; from a non-host it's rejected with an
 *    `IntentAck(accepted=false)` and no session is created.
 *  - A second `StartHand` while a hand is in progress is rejected.
 *  - `SubmitIntent` (valid) applies + broadcasts; (out-of-turn) is
 *    rejected and leaves engine state untouched; (duplicate nonce) is
 *    idempotently accepted without re-applying.
 *  - `RequestNextHand` from any seated player (not just the host)
 *    advances to the next hand.
 *  - `GameStateSnapshot` is scrubbed per recipient — the viewer sees
 *    their own hole cards, never an opponent's.
 *  - `GameEventOccurred` frames carry monotonically increasing
 *    sequence numbers.
 *  - A socket dropping mid-hand doesn't stall the engine — the other
 *    seat's action still processes.
 *
 * NOT covered here: engine rule correctness (`:libraries:gameplay`),
 * reconnect/hydration (covered in [RoomSocketRoutesTest]), and the
 * client-side frame projection ([RemotePokerSessionTest]).
 */
@OptIn(ExperimentalTime::class)
class RoomSocketGameplayRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    // ===================================================================
    // StartHand
    // ===================================================================

    @Test
    fun startHand_fromHost_acksAccepted_andBroadcastsGameStateSnapshot() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withApp(rooms) { client ->
            val socket = client.connect(room.code, host)
            try {
                socket.drainLobbySnapshot()
                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "start-1"))

                val ack = socket.receiveUntilAck("start-1")
                assertTrue(ack.accepted, "host start should be accepted; error=${ack.error}")

                val snapshot = socket.receiveUntilGameState()
                assertEquals(1, snapshot.state.handNumber)
                assertEquals(2, snapshot.state.seats.size)
            } finally {
                socket.closeQuietly()
            }
        }
    }

    @Test
    fun startHand_fromNonHost_isRejected_andNoSessionCreated() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withApp(rooms, registry) { client ->
            val socket = client.connect(room.code, alice)
            try {
                socket.drainLobbySnapshot()
                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "nh-1"))

                val ack = socket.receiveUntilAck("nh-1")
                assertTrue(!ack.accepted, "non-host start must be rejected")
                assertTrue(
                    ack.error?.contains("host") == true,
                    "rejection should explain host-gating; was '${ack.error}'",
                )
                // Defense-in-depth: the engine never started.
                assertNull(registry.peek(room.code), "no session should exist after a rejected start")
            } finally {
                socket.closeQuietly()
            }
        }
    }

    @Test
    fun startHand_whenHandInProgress_isRejected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withApp(rooms) { client ->
            val socket = client.connect(room.code, host)
            try {
                socket.drainLobbySnapshot()
                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s1"))
                assertTrue(socket.receiveUntilAck("s1").accepted)
                socket.receiveUntilGameState() // hand is now live

                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s2"))
                val ack = socket.receiveUntilAck("s2")
                assertTrue(!ack.accepted, "second start mid-hand must be rejected")
                assertTrue(
                    ack.error?.contains("progress") == true,
                    "rejection should explain a hand is already running; was '${ack.error}'",
                )
            } finally {
                socket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // SubmitIntent
    // ===================================================================

    @Test
    fun submitIntent_validFold_isAccepted_andBroadcastsCompletedHand() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainLobbySnapshot()
                aliceSocket.drainLobbySnapshot()

                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                // Act as whichever seat the engine put on the button —
                // heads-up the first actor varies with the shuffle.
                val actingSeat = opening.state.actingSeatIndex!!
                val actorUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val actorSocket = if (actorUser == host.value.toString()) hostSocket else aliceSocket

                actorSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(
                        intent = PlayerIntent.Fold(seatIndex = actingSeat),
                        clientNonce = "fold-1",
                    ),
                )
                assertTrue(actorSocket.receiveUntilAck("fold-1").accepted)

                // Heads-up fold ends the hand; a Complete snapshot fans out.
                val completed = actorSocket.receiveUntilGameState { it.street == BettingRound.Complete }
                assertEquals(BettingRound.Complete, completed.state.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun submitIntent_outOfTurn_isRejected_andStateUnchanged() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withApp(rooms, registry) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainLobbySnapshot()
                aliceSocket.drainLobbySnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                val actingSeat = opening.state.actingSeatIndex!!
                val idleSeat = opening.state.seats.first { it.index != actingSeat }
                val idleUser = idleSeat.playerId!!
                val idleSocket = if (idleUser == host.value.toString()) hostSocket else aliceSocket
                val before = registry.peek(room.code)!!.state.value!!

                idleSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(
                        intent = PlayerIntent.Fold(seatIndex = idleSeat.index),
                        clientNonce = "oot-1",
                    ),
                )
                val ack = idleSocket.receiveUntilAck("oot-1")
                assertTrue(!ack.accepted, "an out-of-turn fold must be rejected")
                assertTrue(
                    ack.error?.contains("turn") == true,
                    "rejection should explain it's not their turn; was '${ack.error}'",
                )

                val after = registry.peek(room.code)!!.state.value!!
                assertEquals(before.street, after.street, "rejected intent must not advance the street")
                assertEquals(
                    before.actingSeatIndex,
                    after.actingSeatIndex,
                    "rejected intent must not move the action",
                )
                assertEquals(before.handNumber, after.handNumber)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun submitIntent_duplicateNonce_isAcceptedTwice_butAppliedOnce() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withApp(rooms, registry) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainLobbySnapshot()
                aliceSocket.drainLobbySnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                val actingSeat = opening.state.actingSeatIndex!!
                val actorUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val actorSocket = if (actorUser == host.value.toString()) hostSocket else aliceSocket
                val foldFrame = RoomClientFrame.SubmitIntent(
                    intent = PlayerIntent.Fold(seatIndex = actingSeat),
                    clientNonce = "dup",
                )

                actorSocket.sendFrame(foldFrame)
                assertTrue(actorSocket.receiveUntilAck("dup").accepted)
                actorSocket.receiveUntilGameState { it.street == BettingRound.Complete }
                val afterFirst = registry.peek(room.code)!!.state.value!!

                // Resubmit the same nonce — server dedupe returns Accepted
                // without re-running the engine.
                actorSocket.sendFrame(foldFrame)
                val secondAck = actorSocket.receiveUntilAck("dup")
                assertTrue(secondAck.accepted, "a duplicate nonce stays idempotently accepted")

                val afterSecond = registry.peek(room.code)!!.state.value!!
                assertEquals(
                    afterFirst.handNumber,
                    afterSecond.handNumber,
                    "duplicate intent must not advance the hand",
                )
                assertEquals(BettingRound.Complete, afterSecond.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // RequestNextHand
    // ===================================================================

    @Test
    fun requestNextHand_fromNonHost_advancesToNextHand() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainLobbySnapshot()
                aliceSocket.drainLobbySnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                // Complete hand one by folding the actor.
                val actingSeat = opening.state.actingSeatIndex!!
                val actorUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val actorSocket = if (actorUser == host.value.toString()) hostSocket else aliceSocket
                actorSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = actingSeat), "fold-1"),
                )
                assertTrue(actorSocket.receiveUntilAck("fold-1").accepted)
                actorSocket.receiveUntilGameState { it.street == BettingRound.Complete }

                // The non-host (Alice) advances — there's no host gate on
                // requestNextHand.
                aliceSocket.sendFrame(RoomClientFrame.RequestNextHand(clientNonce = "next-1"))
                assertTrue(aliceSocket.receiveUntilAck("next-1").accepted)

                val secondHand = aliceSocket.receiveUntilGameState { it.handNumber == 2 }
                assertEquals(2, secondHand.state.handNumber)
                assertEquals(BettingRound.Preflop, secondHand.state.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // Broadcast contract — scrubbing + sequencing
    // ===================================================================

    @Test
    fun gameStateSnapshot_isScrubbedPerRecipient() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            try {
                hostSocket.drainLobbySnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)

                val snapshot = hostSocket.receiveUntilGameState().state
                val mySeat = snapshot.seats.single { it.playerId == host.value.toString() }
                val opponentSeat = snapshot.seats.single { it.playerId == alice.value.toString() }

                assertEquals(2, mySeat.holeCards.size, "viewer must see their own two hole cards")
                assertTrue(
                    opponentSeat.holeCards.isEmpty(),
                    "preflop opponent hole cards must be scrubbed for the viewer",
                )
            } finally {
                hostSocket.closeQuietly()
            }
        }
    }

    @Test
    fun gameEventOccurred_carriesMonotonicSequence() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            try {
                hostSocket.drainLobbySnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)

                // The opening burst (HandStarted, blinds, …) is several
                // events — collect a few and assert strict monotonicity.
                val seqs = buildList {
                    repeat(3) { add(hostSocket.receiveUntilEvent().seq) }
                }
                assertEquals(
                    seqs.sorted(),
                    seqs,
                    "event sequences must arrive non-decreasing; got $seqs",
                )
                assertEquals(
                    seqs.toSet().size,
                    seqs.size,
                    "event sequences must be distinct; got $seqs",
                )
            } finally {
                hostSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // Resilience
    // ===================================================================

    @Test
    fun socketDisconnect_midHand_engineContinues_forOtherSeat() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withApp(rooms, registry) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainLobbySnapshot()
                aliceSocket.drainLobbySnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                val actingSeat = opening.state.actingSeatIndex!!
                val actingUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val idleUser = opening.state.seats.first { it.index != actingSeat }.playerId!!
                val actorSocket = if (actingUser == host.value.toString()) hostSocket else aliceSocket
                val idleSocket = if (idleUser == host.value.toString()) hostSocket else aliceSocket
                val idleUserId = if (idleUser == host.value.toString()) host else alice

                // The non-acting player's socket drops mid-hand.
                idleSocket.closeQuietly()
                awaitDisconnected(rooms, room.code, idleUserId)

                // The acting player's action still processes — the engine
                // isn't bound to any single connection.
                actorSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = actingSeat), "fold-1"),
                )
                assertTrue(
                    actorSocket.receiveUntilAck("fold-1").accepted,
                    "the surviving socket's intent must still be processed",
                )
                assertEquals(BettingRound.Complete, registry.peek(room.code)!!.state.value!!.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // Scaffolding
    // ===================================================================

    private fun newRoomService() = InMemoryRoomService(
        clock = FixedClock(),
        random = Random(0L),
    )

    private fun newRegistry(): GameSessionRegistry = DefaultGameSessionRegistry(
        snapshotStore = NoOpSessionSnapshotStore(),
        clock = Clock.System,
    )

    private fun jwt(forUserId: UserId): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(forUserId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun withApp(
        rooms: InMemoryRoomService,
        gameSessions: GameSessionRegistry = newRegistry(),
        block: suspend (HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                installWebSockets()
                routing {
                    roomSocketRoutes(
                        rooms = rooms,
                        gameSessions = gameSessions,
                        reaperGrace = 5.minutes,
                    )
                }
            }
            val raw = createClient {
                install(ClientWebSockets)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(raw)
        }
    }

    private suspend fun HttpClient.connect(code: String, asUser: UserId): ClientWebSocketSession =
        webSocketSession(
            method = HttpMethod.Get,
            host = "localhost",
            port = 80,
            path = "/v1/rooms/$code/socket",
        ) {
            header(HttpHeaders.Authorization, "Bearer ${jwt(asUser)}")
        }

    private suspend fun ClientWebSocketSession.closeQuietly() {
        runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "test done")) }
    }

    private suspend fun ClientWebSocketSession.sendFrame(frame: RoomClientFrame) {
        send(Frame.Text(json.encodeToString(RoomClientFrame.serializer(), frame)))
    }

    private suspend fun ClientWebSocketSession.receiveOne(): RoomSocketEventDto = withTimeout(10_000) {
        val frame = incoming.receive()
        val text = (frame as Frame.Text).readText()
        json.decodeFromString(RoomSocketEventDto.serializer(), text)
    }

    /** The lobby Snapshot is always the first frame after connect. */
    private suspend fun ClientWebSocketSession.drainLobbySnapshot() {
        receiveUntil<RoomSocketEventDto.Snapshot>()
    }

    private suspend fun ClientWebSocketSession.receiveUntilAck(
        nonce: String,
    ): RoomSocketEventDto.IntentAck = receiveUntil { it.clientNonce == nonce }

    private suspend fun ClientWebSocketSession.receiveUntilGameState(
        predicate: (GameState) -> Boolean = { true },
    ): RoomSocketEventDto.GameStateSnapshot = receiveUntil { predicate(it.state) }

    private suspend fun ClientWebSocketSession.receiveUntilEvent(): RoomSocketEventDto.GameEventOccurred =
        receiveUntil()

    /**
     * Drain-and-discard frames until one of type [T] matching [predicate]
     * arrives. A single socket multiplexes room-publisher, game-publisher
     * and ack frames in coroutine-scheduling order, so tests assert on
     * the frame they care about and skip the rest. Bounded so a missing
     * frame fails the test instead of hanging.
     */
    private suspend inline fun <reified T : RoomSocketEventDto> ClientWebSocketSession.receiveUntil(
        predicate: (T) -> Boolean = { true },
    ): T {
        repeat(MAX_FRAMES_PER_WAIT) {
            val event = receiveOne()
            if (event is T && predicate(event)) return event
        }
        error("did not receive a matching ${T::class.simpleName} within $MAX_FRAMES_PER_WAIT frames")
    }

    private suspend fun awaitDisconnected(
        rooms: InMemoryRoomService,
        code: String,
        userId: UserId,
        timeoutMs: Long = 2_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val member = rooms.find(code)?.memberFor(userId) ?: return
            if (!member.isConnected) return
            delay(20)
        }
        error("Member $userId stayed isConnected=true beyond ${timeoutMs}ms")
    }

    private class FixedClock(private val ms: Long = 1_700_000_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
    }

    private companion object {
        const val MAX_FRAMES_PER_WAIT = 60
    }
}
