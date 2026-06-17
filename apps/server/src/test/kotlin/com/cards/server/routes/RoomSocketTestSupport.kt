package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.DefaultGameSessionRegistry
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.game.NoOpSessionSnapshotStore
import com.dangerfield.cards.server.game.SessionSnapshot
import com.dangerfield.cards.server.game.SessionSnapshotStore
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
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Shared scaffolding for the per-room WebSocket protocol tests
 * ([RoomSocketRoutesTest] — lobby / presence / reconnect, and
 * [RoomSocketGameplayRoutesTest] — the gameplay leg). Both stand up a real
 * Ktor server with the production plugins + route and drive it through a
 * real client socket, so the scaffolding here is the one place that knows
 * how to bring that pair up, authenticate it, and read its wire protocol
 * safely.
 *
 * ### The frame-ordering hazard (why [RoomSocketTestClient] buffers)
 *
 * A single per-room socket multiplexes **three independent server
 * coroutines** writing to it (see [roomSocketRoutes]): the room publisher
 * (lobby snapshots + presence deltas), the game publisher (game-state
 * snapshots + events), and the drain loop (per-intent acks). There is **no
 * ordering guarantee between those streams** — e.g. a `StartHand`'s
 * [RoomSocketEventDto.GameStateSnapshot] (game publisher) routinely races
 * its [RoomSocketEventDto.IntentAck] (drain loop) and can arrive first.
 *
 * A naive "read until I see the frame I want, discarding the rest" helper
 * silently drops whatever it skipped. When two `receiveUntil*` calls run
 * back-to-back and the second one's frame happens to arrive *before* the
 * first one's, the first call eats it, and the second then blocks to its
 * timeout on a frame that no longer exists. That was a real, timing-
 * dependent CI flake (a handful of random `TimeoutCancellationException`s
 * per run).
 *
 * [RoomSocketTestClient] closes that hole by keeping skipped frames in a
 * per-socket FIFO buffer and consulting it before reading the wire, so
 * every `receiveUntil*` is order-independent. Strictly *positional* reads
 * remain available via [RoomSocketTestClient.receiveOne] for the lobby
 * tests that intentionally assert a single publisher's emission order
 * (snapshot-before-delta) — when no `receiveUntil*` has run, the buffer is
 * empty and [RoomSocketTestClient.receiveOne] reads straight off the wire,
 * so that ordering assertion is preserved.
 */
@OptIn(ExperimentalTime::class)
internal object RoomSocketTestAuth {
    const val ISSUER = "https://test-project.supabase.co/auth/v1"
    const val SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef"

    /** A signed Supabase-shaped JWT for [userId], valid for one minute. */
    fun jwt(userId: UserId): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience("authenticated")
        .withSubject(userId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(SECRET))

    /** The verifier the test server installs — the mirror of [jwt]. */
    val verifier = JWT.require(Algorithm.HMAC256(SECRET))
        .withIssuer(ISSUER)
        .withAudience("authenticated")
        .build()
}

/**
 * A [Clock] pinned to a fixed instant so room/member timestamps and
 * snapshot `updatedAt` values are deterministic across runs.
 */
@OptIn(ExperimentalTime::class)
internal class FixedClock(private val ms: Long = 1_700_000_000_000) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
}

/** A deterministic in-memory room service (fixed clock, seeded RNG). */
internal fun newRoomService(): InMemoryRoomService = InMemoryRoomService(
    clock = FixedClock(),
    random = Random(0L),
)

/** A registry with no durable persistence — the default for socket tests. */
@OptIn(ExperimentalTime::class)
internal fun newRegistry(): GameSessionRegistry = DefaultGameSessionRegistry(
    snapshotStore = NoOpSessionSnapshotStore(),
    clock = Clock.System,
)

/**
 * An in-memory [SessionSnapshotStore] for the restart/hydration tests —
 * the durable store a previous server process would have written to.
 */
internal class InMemoryTestSnapshotStore : SessionSnapshotStore {
    private val rows = mutableMapOf<String, SessionSnapshot>()
    private val mutex = Mutex()

    override suspend fun upsert(snapshot: SessionSnapshot) {
        mutex.withLock { rows[snapshot.code] = snapshot }
    }

    override suspend fun readByCode(code: String): SessionSnapshot? =
        mutex.withLock { rows[code] }

    override suspend fun deleteByCode(code: String) {
        mutex.withLock { rows.remove(code) }
    }
}

/**
 * Polls [rooms] until [userId]'s member is gone or marked disconnected,
 * then returns. The server's onClose handler flips `isConnected` in a
 * separate coroutine, so a direct assertion would race the flip; polling
 * a real wall-clock deadline (the `delay` is virtual under `runTest`)
 * waits it out and fails loudly if it never lands.
 */
internal suspend fun awaitDisconnected(
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

/**
 * Polls [rooms] until [userId]'s seat has been freed by the reaper. Same
 * rationale as [awaitDisconnected]: the reaper fires from a scheduled
 * coroutine after the grace window, so the assertion can't read the map
 * synchronously.
 */
internal suspend fun awaitReaped(
    rooms: InMemoryRoomService,
    code: String,
    userId: UserId,
    timeoutMs: Long = 2_000,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (rooms.find(code)?.memberFor(userId) == null) return
        delay(20)
    }
    error("Member $userId still seated after ${timeoutMs}ms — reaper didn't fire")
}

/**
 * Brings up a real Ktor test server with the production socket route +
 * plugins and hands [block] a [RoomSocketTestApp] for opening client
 * sockets. Any socket opened through the app is force-closed on teardown,
 * so individual tests don't have to manage lifecycle.
 *
 * @param gameSessions the registry backing gameplay — defaults to a
 *  non-persisting [newRegistry]; pass an explicit one (e.g. wired to an
 *  [InMemoryTestSnapshotStore]) to exercise the restart/hydration path.
 * @param reaperGrace the disconnect→seat-free grace window; default is the
 *  production value, override with a tight one to test the reaper without
 *  sitting on a real timer.
 */
@OptIn(ExperimentalTime::class)
internal suspend fun withRoomSocketTestApp(
    rooms: InMemoryRoomService,
    gameSessions: GameSessionRegistry = newRegistry(),
    reaperGrace: Duration = 5.minutes,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository = EmptyEquipmentRepository,
    block: suspend (RoomSocketTestApp) -> Unit,
) {
    testApplication {
        application {
            installSerialization()
            installStatusPages()
            installAuthenticationWithVerifier(RoomSocketTestAuth.verifier)
            installWebSockets()
            routing {
                roomSocketRoutes(
                    rooms = rooms,
                    gameSessions = gameSessions,
                    equipmentRepository = equipmentRepository,
                    reaperGrace = reaperGrace,
                )
            }
        }
        val raw = createClient {
            install(ClientWebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val app = RoomSocketTestApp(raw)
        try {
            block(app)
        } finally {
            app.closeAll()
        }
    }
}

/**
 * Opens authenticated client sockets against the running test server and
 * tracks them so [withRoomSocketTestApp] can close any left open when the
 * block returns.
 */
internal class RoomSocketTestApp(private val raw: HttpClient) {
    private val opened = mutableListOf<RoomSocketTestClient>()

    /**
     * Connects [asUser] to room [code] and returns the client. The socket
     * stays open until the test closes it or [withRoomSocketTestApp] tears
     * the app down — callers that open several sockets at once don't need a
     * `finally`.
     */
    suspend fun connect(code: String, asUser: UserId): RoomSocketTestClient {
        val session = raw.webSocketSession(
            method = HttpMethod.Get,
            host = "localhost",
            port = 80,
            path = "/v1/rooms/$code/socket",
        ) {
            header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(asUser)}")
        }
        return RoomSocketTestClient(session).also { opened += it }
    }

    /**
     * Scoped variant of [connect] that closes the socket when [block]
     * returns — the natural fit for a test that drives a single socket.
     */
    suspend fun openSocket(
        code: String,
        asUser: UserId,
        block: suspend (RoomSocketTestClient) -> Unit,
    ) {
        val client = connect(code, asUser)
        try {
            block(client)
        } finally {
            client.closeQuietly()
        }
    }

    internal suspend fun closeAll() {
        opened.forEach { it.closeQuietly() }
    }
}

/**
 * A test-side view of one open room socket. Decodes inbound frames and —
 * crucially — buffers the ones a given wait skips so a later wait can
 * still find them (see [RoomSocketTestAuth]'s file-level note for the
 * ordering hazard this guards against).
 *
 * Two read styles:
 *  - `receiveUntil*` — order-independent: returns the next matching frame
 *    whether it's already buffered or still on the wire, keeping the rest.
 *    Use these whenever more than one server stream is live (any gameplay
 *    test, or any test that has both an ack and a snapshot in flight).
 *  - [receiveOne] — strictly positional: the next frame in arrival order
 *    (buffer first, then the wire). Use this only when asserting a single
 *    publisher's emission order, e.g. snapshot-immediately-before-delta.
 */
internal class RoomSocketTestClient(val session: ClientWebSocketSession) {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    /** Frames pulled off the wire while waiting for a *different* frame. */
    private val pending = ArrayDeque<RoomSocketEventDto>()

    /** Encode + send a client→server frame. */
    suspend fun sendFrame(frame: RoomClientFrame) {
        session.send(Frame.Text(json.encodeToString(RoomClientFrame.serializer(), frame)))
    }

    /**
     * The next frame in arrival order: a buffered one if any was set aside
     * by a prior `receiveUntil*`, otherwise the next off the wire. Fails
     * fast on a quiet socket so a hung test surfaces instead of stalling.
     */
    suspend fun receiveOne(): RoomSocketEventDto =
        pending.removeFirstOrNull() ?: readFromWire()

    /** The lobby [RoomSocketEventDto.Snapshot] — first frame after connect. */
    suspend fun drainSnapshot(): RoomSocketEventDto.Snapshot =
        receiveMatching { it is RoomSocketEventDto.Snapshot } as RoomSocketEventDto.Snapshot

    /** The [RoomSocketEventDto.IntentAck] correlated to [nonce]. */
    suspend fun receiveUntilAck(nonce: String): RoomSocketEventDto.IntentAck =
        receiveMatching {
            it is RoomSocketEventDto.IntentAck && it.clientNonce == nonce
        } as RoomSocketEventDto.IntentAck

    /** The next [RoomSocketEventDto.GameStateSnapshot] whose state matches [predicate]. */
    suspend fun receiveUntilGameState(
        predicate: (GameState) -> Boolean = { true },
    ): RoomSocketEventDto.GameStateSnapshot =
        receiveMatching {
            it is RoomSocketEventDto.GameStateSnapshot && predicate(it.state)
        } as RoomSocketEventDto.GameStateSnapshot

    /** The next [RoomSocketEventDto.GameEventOccurred] frame. */
    suspend fun receiveUntilEvent(): RoomSocketEventDto.GameEventOccurred =
        receiveMatching { it is RoomSocketEventDto.GameEventOccurred } as RoomSocketEventDto.GameEventOccurred

    /** The next frame of type [type] matching [predicate]. */
    suspend fun <T : RoomSocketEventDto> receiveUntil(
        type: KClass<T>,
        predicate: (T) -> Boolean = { true },
    ): T = type.java.cast(receiveMatching { type.isInstance(it) && predicate(type.java.cast(it)) })

    /**
     * True if the socket closed (server-side close frame or a closed
     * receive channel) before yielding a usable frame. Used by the
     * rejection tests; reads the wire directly rather than the buffer
     * because it's only ever called as the very first read on a socket.
     */
    suspend fun expectClosed(): Boolean = try {
        withTimeout(CLOSE_TIMEOUT_MS) {
            session.incoming.receive() is Frame.Close
        }
    } catch (_: ClosedReceiveChannelException) {
        true
    } catch (_: Throwable) {
        // Any other failure also means the server tore the socket down.
        true
    }

    suspend fun closeQuietly() {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "test done")) }
    }

    /**
     * Returns the next frame satisfying [match], scanning already-buffered
     * frames first (in arrival order) and then reading new ones, stashing
     * every non-match back into the buffer so it stays available to later
     * waits. Bounded on fresh reads so a genuinely missing frame fails the
     * test instead of hanging forever.
     */
    private suspend fun receiveMatching(match: (RoomSocketEventDto) -> Boolean): RoomSocketEventDto {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (match(event)) {
                iterator.remove()
                return event
            }
        }
        repeat(MAX_FRAMES_PER_WAIT) {
            val event = readFromWire()
            if (match(event)) return event
            pending.addLast(event)
        }
        error("did not receive a matching frame within $MAX_FRAMES_PER_WAIT frames")
    }

    private suspend fun readFromWire(): RoomSocketEventDto = withTimeout(RECEIVE_TIMEOUT_MS) {
        val frame = session.incoming.receive()
        val text = (frame as Frame.Text).readText()
        json.decodeFromString(RoomSocketEventDto.serializer(), text)
    }

    private companion object {
        /** Per-frame wire-read budget. Generous so CI's slower scheduler
         *  has headroom, while still failing fast on a genuine stall. */
        const val RECEIVE_TIMEOUT_MS = 10_000L

        /** A close is expected promptly; don't sit on the full budget. */
        const val CLOSE_TIMEOUT_MS = 5_000L

        /** Cap on fresh reads per wait, so a missing frame fails loudly. */
        const val MAX_FRAMES_PER_WAIT = 60
    }
}

/** No-op equipment source — room-socket tests don't exercise badge resolution. */
@OptIn(kotlin.time.ExperimentalTime::class)
private object EmptyEquipmentRepository : com.dangerfield.cards.server.domain.EquipmentRepository {
    override suspend fun listEquipped(
        userId: com.dangerfield.cards.server.domain.UserId,
    ): List<com.dangerfield.cards.server.domain.EquippedItem> = emptyList()

    override suspend fun equip(
        userId: com.dangerfield.cards.server.domain.UserId,
        productId: String,
        newUpdatedAt: kotlin.time.Instant,
    ): com.dangerfield.cards.server.domain.EquippedItem = error("equip not used in room-socket tests")

    override suspend fun unequip(
        userId: com.dangerfield.cards.server.domain.UserId,
        productId: String,
        opUpdatedAt: kotlin.time.Instant,
    ): com.dangerfield.cards.server.domain.EquippedItem? = null
}
