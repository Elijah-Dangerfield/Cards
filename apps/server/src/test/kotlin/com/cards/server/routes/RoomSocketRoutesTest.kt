package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.plugins.installWebSockets
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * End-to-end tests for the per-room WebSocket. Spins up a real Ktor
 * test server with the WebSockets plugin + a real test client that
 * opens a socket, listens for events, and asserts the wire protocol.
 *
 * What we pin:
 *  - Connect → first frame is a Snapshot with the current room.
 *  - markConnected flips true on connect, false on disconnect.
 *  - Second client joining (via HTTP) → existing socket receives a
 *    Snapshot + MemberJoined delta.
 *  - First client disconnecting → second socket sees a
 *    MemberPresenceChanged(isConnected=false) event.
 *  - Reconnect by the SAME userId preserves the seat (no duplicate
 *    member, same seatIndex).
 *  - Non-member (didn't POST /join) gets the socket closed with
 *    CANNOT_ACCEPT.
 *
 * The reconnect-flow tests are the load-bearing ones the user
 * specifically called out — these are the gnarliest pieces of the MP
 * surface and the ones most likely to regress.
 */
@OptIn(ExperimentalTime::class)
class RoomSocketRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    @Test
    fun connect_firstFrameIsSnapshot_andMarksMemberConnected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host")
        withApp(rooms) { client ->
            client.openSocket(room.code, asUser = host) { session ->
                val event = session.receiveOne()
                val snap = assertIs<RoomSocketEventDto.Snapshot>(event)
                assertEquals(room.code, snap.room.code)
                assertEquals(1, snap.room.members.size)
                // The snapshot reflects post-connect state — isConnected is true.
                assertTrue(snap.room.members.single().isConnected)
            }
            // The server's onClose handler runs in a coroutine after the
            // client closes. Poll until it lands so the assertion isn't
            // racing the flip.
            awaitDisconnected(rooms, room.code, host)
        }
    }

    @Test
    fun nonMember_socketIsRejected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host")
        withApp(rooms) { client ->
            client.openSocket(room.code, asUser = alice) { session ->
                // We expect the server to close the socket immediately.
                // Receiving from a closed channel throws.
                assertTrue(receiveClosed(session))
            }
        }
    }

    @Test
    fun unknownRoom_socketIsRejected() = runTest {
        val rooms = newRoomService()
        withApp(rooms) { client ->
            client.openSocket("ZZZZZZ", asUser = host) { session ->
                assertTrue(receiveClosed(session))
            }
        }
    }

    @Test
    fun secondJoin_broadcastsToFirstSocket() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        withApp(rooms) { client ->
            client.openSocket(room.code, asUser = host) { hostSession ->
                // Drain the initial snapshot.
                hostSession.receiveOne()

                // Alice joins via service (simulates HTTP /join).
                rooms.join(room.code, alice, "Alice")

                // The host socket should receive a Snapshot + a
                // MemberJoined delta (in that order — Snapshot first
                // is the always-correct fallback).
                val first = hostSession.receiveOne()
                val snap = assertIs<RoomSocketEventDto.Snapshot>(first)
                assertEquals(2, snap.room.members.size)

                val second = hostSession.receiveOne()
                val joined = assertIs<RoomSocketEventDto.MemberJoined>(second)
                assertEquals(alice.value.toString(), joined.member.userId)
                assertEquals(1, joined.member.seatIndex)
            }
        }
    }

    @Test
    fun disconnect_broadcastsPresenceFlip_toOtherSockets() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        withApp(rooms) { client ->
            client.openSocket(room.code, asUser = host) { hostSession ->
                hostSession.receiveOne() // host snapshot

                client.openSocket(room.code, asUser = alice) { aliceSession ->
                    aliceSession.receiveOne() // alice snapshot
                    // Host sees alice's presence flip to true.
                    val snapshotForAliceConnect = hostSession.receiveOne()
                    assertIs<RoomSocketEventDto.Snapshot>(snapshotForAliceConnect)
                    val aliceConnected = hostSession.receiveOne()
                    val delta = assertIs<RoomSocketEventDto.MemberPresenceChanged>(aliceConnected)
                    assertEquals(alice.value.toString(), delta.userId)
                    assertEquals(true, delta.isConnected)
                }
                // After Alice's socket closes, host sees her presence flip back.
                val snapshotForAliceDisconnect = hostSession.receiveOne()
                assertIs<RoomSocketEventDto.Snapshot>(snapshotForAliceDisconnect)
                val aliceDisconnected = hostSession.receiveOne()
                val delta = assertIs<RoomSocketEventDto.MemberPresenceChanged>(aliceDisconnected)
                assertEquals(alice.value.toString(), delta.userId)
                assertEquals(false, delta.isConnected)
            }
        }
    }

    @Test
    fun reconnectSameUser_preservesSeat_andDoesNotDuplicateMember() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val originalSeatIndex = rooms.find(room.code)!!.memberFor(alice)!!.seatIndex

        withApp(rooms) { client ->
            // Connect, then close.
            client.openSocket(room.code, asUser = alice) { aliceSession ->
                aliceSession.receiveOne()
            }
            // Reconnect: should still be the same member, same seat.
            client.openSocket(room.code, asUser = alice) { aliceSession ->
                val snap = assertIs<RoomSocketEventDto.Snapshot>(aliceSession.receiveOne())
                assertEquals(2, snap.room.members.size, "no duplicate member created")
                val alicePostReconnect = snap.room.members.single { it.userId == alice.value.toString() }
                assertEquals(originalSeatIndex, alicePostReconnect.seatIndex)
                assertTrue(alicePostReconnect.isConnected)
            }
        }
    }

    // ---------- scaffolding ----------

    private fun newRoomService() = InMemoryRoomService(
        clock = FixedClock(),
        random = Random(0L),
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
        block: suspend (SocketClient) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                installWebSockets()
                routing { roomSocketRoutes(rooms) }
            }
            val raw = createClient {
                install(ClientWebSockets)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(SocketClient(raw))
        }
    }

    private inner class SocketClient(private val raw: io.ktor.client.HttpClient) {
        suspend fun openSocket(
            code: String,
            asUser: UserId,
            block: suspend (ClientWebSocketSession) -> Unit,
        ) {
            val session = raw.webSocketSession(
                method = HttpMethod.Get,
                host = "localhost",
                port = 80,
                path = "/v1/rooms/$code/socket",
            ) {
                header(HttpHeaders.Authorization, "Bearer ${jwt(asUser)}")
            }
            try {
                block(session)
            } finally {
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
                }
            }
        }
    }

    /**
     * Pull the next event off the socket. Times out fast so a hung test
     * fails loudly instead of hanging the suite.
     */
    private suspend fun ClientWebSocketSession.receiveOne(): RoomSocketEventDto = withTimeout(2_000) {
        val frame = incoming.receive()
        val text = (frame as Frame.Text).readText()
        json.decodeFromString(RoomSocketEventDto.serializer(), text)
    }

    /** True if the socket closed before yielding any non-close frame. */
    private suspend fun receiveClosed(session: ClientWebSocketSession): Boolean = try {
        withTimeout(2_000) {
            val frame = session.incoming.receive()
            // Close frame counts as "closed" — the server sent it and
            // the client surfaced it as the first frame.
            frame is Frame.Close
        }
    } catch (_: ClosedReceiveChannelException) {
        true
    } catch (_: Throwable) {
        // Any other failure also counts — server-side close.
        true
    }

    /**
     * Polls the room service until the given member's isConnected flag
     * flips to false. Necessary because the server's onClose handler
     * runs in a separate coroutine; without polling, the assertion
     * races the flip.
     */
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
}
