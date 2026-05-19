package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [RoomRepositoryImpl]'s HTTP-status → outcome mapping. The
 * WebSocket flow has its own test in [ReconnectingRoomSocketTest].
 *
 * Strategy: wrap the real [HttpRoomApi] around a Ktor HttpClient with
 * MockEngine, so the assertions exercise the actual interceptor +
 * deserialization path. Misses bugs from a hand-rolled fake.
 */
class RoomRepositoryImplTest {

    @Test
    fun createRoom_200_returnsSuccess_withRoom() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ROOM_RESPONSE_JSON) })
        val outcome = repo.createRoom(maxSeats = 4)
        val success = assertIs<CreateRoomOutcome.Success>(outcome)
        assertEquals("ABC123", success.room.code)
        assertEquals(1, success.room.members.size)
    }

    @Test
    fun createRoom_400_returnsInvalidMaxSeats_withServerMessage() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.BadRequest,
                """{"error":{"code":"invalid_max_seats","message":"maxSeats must be 2..9"}}""",
            )
        })
        val outcome = repo.createRoom(maxSeats = 1)
        val invalid = assertIs<CreateRoomOutcome.InvalidMaxSeats>(outcome)
        assertTrue(invalid.message.contains("maxSeats"))
    }

    @Test
    fun createRoom_401_returnsNotSignedIn() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<CreateRoomOutcome.NotSignedIn>(repo.createRoom())
    }

    @Test
    fun joinRoom_200_alreadyJoinedFalse() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, JOIN_RESPONSE_JSON_FRESH) })
        val outcome = repo.joinRoom("ABC123")
        val success = assertIs<JoinRoomOutcome.Success>(outcome)
        assertEquals(false, success.alreadyJoined)
    }

    @Test
    fun joinRoom_200_alreadyJoinedTrue() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, JOIN_RESPONSE_JSON_REJOIN) })
        val outcome = repo.joinRoom("ABC123")
        val success = assertIs<JoinRoomOutcome.Success>(outcome)
        assertEquals(true, success.alreadyJoined)
    }

    @Test
    fun joinRoom_404_returnsNotFound() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.NotFound) })
        assertIs<JoinRoomOutcome.NotFound>(repo.joinRoom("ZZZZZZ"))
    }

    @Test
    fun joinRoom_409_roomFull_returnsFull() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.Conflict,
                """{"error":{"code":"room_full","message":"That room is full."}}""",
            )
        })
        assertIs<JoinRoomOutcome.Full>(repo.joinRoom("ABC123"))
    }

    @Test
    fun joinRoom_409_notJoinable_returnsNotJoinable() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.Conflict,
                """{"error":{"code":"room_not_joinable","message":"..."}}""",
            )
        })
        assertIs<JoinRoomOutcome.NotJoinable>(repo.joinRoom("ABC123"))
    }

    @Test
    fun leaveRoom_204_returnsSuccess() = runTest {
        val repo = newRepo(MockEngine { respond(content = "", status = HttpStatusCode.NoContent) })
        assertIs<LeaveRoomOutcome.Success>(repo.leaveRoom("ABC123"))
    }

    @Test
    fun leaveRoom_404_returnsNotFound() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.NotFound) })
        assertIs<LeaveRoomOutcome.NotFound>(repo.leaveRoom("ABC123"))
    }

    @Test
    fun leaveRoom_409_returnsNotInRoom() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Conflict) })
        assertIs<LeaveRoomOutcome.NotInRoom>(repo.leaveRoom("ABC123"))
    }

    @Test
    fun joinRoom_transportError_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { throw SimulatedNetworkError("connection refused") })
        val outcome = repo.joinRoom("ABC123")
        val networkError = assertIs<JoinRoomOutcome.NetworkError>(outcome)
        assertTrue(networkError.cause is SimulatedNetworkError)
    }

    /**
     * Cross-platform stand-in for `java.io.IOException` — see the sibling
     * comment in `ReconnectingRoomSocketTest`.
     */
    private class SimulatedNetworkError(message: String) : RuntimeException(message)

    // ---------- scaffolding ----------

    private fun newRepo(engine: MockEngine): RoomRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            expectSuccess = true
        }
        val api = HttpRoomApi(FakeNetworkClient(client))
        // The socket flow isn't exercised here — empty flow keeps the
        // repo's observeRoom delegation off the critical path.
        val socket = object : RoomSocket {
            override fun observe(code: String): Flow<RoomConnection> = emptyFlow()
        }
        return RoomRepositoryImpl(api, socket)
    }

    private class FakeNetworkClient(private val httpClient: HttpClient) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        status: HttpStatusCode,
        body: String,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", "application/json"),
    )

    companion object {
        private val ROOM_JSON_HOST = """
            {
              "code":"ABC123",
              "hostUserId":"11111111-1111-1111-1111-111111111111",
              "createdAtEpochMs":1700000000000,
              "maxSeats":4,
              "status":"Lobby",
              "members":[
                {
                  "userId":"11111111-1111-1111-1111-111111111111",
                  "displayName":"Host",
                  "seatIndex":0,
                  "joinedAtEpochMs":1700000000000,
                  "isConnected":false
                }
              ]
            }
        """.trimIndent()
        private val ROOM_RESPONSE_JSON = """{"schemaVersion":1,"room":$ROOM_JSON_HOST}"""
        private val JOIN_RESPONSE_JSON_FRESH =
            """{"schemaVersion":1,"alreadyJoined":false,"room":$ROOM_JSON_HOST}"""
        private val JOIN_RESPONSE_JSON_REJOIN =
            """{"schemaVersion":1,"alreadyJoined":true,"room":$ROOM_JSON_HOST}"""
    }
}
