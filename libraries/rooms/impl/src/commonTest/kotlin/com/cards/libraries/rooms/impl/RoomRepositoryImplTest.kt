package com.dangerfield.cards.libraries.rooms.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
class RoomRepositoryImplTest : CoroutineTest() {

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
    fun createRoom_responseWithoutVisibility_defaultsToPrivate() = runTest {
        // The base room JSON carries no visibility field — decodes as Private.
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ROOM_RESPONSE_JSON) })
        val success = assertIs<CreateRoomOutcome.Success>(repo.createRoom())
        assertEquals(
            com.dangerfield.cards.libraries.rooms.RoomVisibility.Private,
            success.room.visibility,
        )
    }

    @Test
    fun createRoom_open_decodesOpenVisibility() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, OPEN_ROOM_RESPONSE_JSON) })
        val success = assertIs<CreateRoomOutcome.Success>(repo.createRoom(open = true))
        assertEquals(
            com.dangerfield.cards.libraries.rooms.RoomVisibility.Open,
            success.room.visibility,
            "an Open room from the server is mirrored on the domain model",
        )
    }

    // Forward-compat: a future server visibility/status this client doesn't know
    // must not crash a release decode of a room snapshot. Pins the actual kotlinx
    // contract (release Json = coerceInputValues on, which lands an unrecognised
    // enum on the *property default*) so the "Unknown safety valve" in RoomDto is
    // grounded, not assumed. Both fields default such that coercion has somewhere
    // to land — visibility → Private, status → Unknown.
    @Test
    fun roomDto_unknownEnums_releaseJsonCoercesInsteadOfCrashing() {
        val releaseJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val unknownVis = releaseJson.decodeFromString(RoomDto.serializer(), UNKNOWN_VIS_ROOM_JSON)
        assertEquals(
            RoomVisibilityDto.Private,
            unknownVis.visibility,
            "unknown visibility coerces to the property default (Private), not a crash",
        )
        val unknownStatus = releaseJson.decodeFromString(RoomDto.serializer(), UNKNOWN_STATUS_ROOM_JSON)
        assertEquals(
            RoomStatusDto.Unknown,
            unknownStatus.status,
            "unknown status coerces to the property default (Unknown), not a crash",
        )
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
    fun joinRoom_400_insufficientBalance_returnsOverBalance() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.BadRequest,
                """{"error":{"code":"insufficient_balance","message":"That table's buy-in is more than your balance of 5000 chips."}}""",
            )
        })
        val outcome = assertIs<JoinRoomOutcome.OverBalance>(repo.joinRoom("ABC123"))
        assertEquals(
            "That table's buy-in is more than your balance of 5000 chips.",
            outcome.message,
        )
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

    @Test
    fun getActiveRooms_200_withRooms_returnsSuccess() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_ONE_ROOM_JSON) })
        val outcome = repo.getActiveRooms()
        val success = assertIs<GetActiveRoomsOutcome.Success>(outcome)
        assertEquals(1, success.rooms.size)
        assertEquals("ABC123", success.rooms.single().code)
    }

    @Test
    fun getActiveRooms_200_empty_returnsSuccess_withEmptyList() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_EMPTY_JSON) })
        val outcome = repo.getActiveRooms()
        val success = assertIs<GetActiveRoomsOutcome.Success>(outcome)
        assertTrue(success.rooms.isEmpty())
    }

    @Test
    fun getActiveRooms_401_returnsNotSignedIn() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<GetActiveRoomsOutcome.NotSignedIn>(repo.getActiveRooms())
    }

    @Test
    fun getActiveRooms_transportError_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { throw SimulatedNetworkError("dns") })
        val outcome = repo.getActiveRooms()
        val networkError = assertIs<GetActiveRoomsOutcome.NetworkError>(outcome)
        assertTrue(networkError.cause is SimulatedNetworkError)
    }

    @Test
    fun observeActiveRooms_seededByGetActiveRooms() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_ONE_ROOM_JSON) })
        assertTrue(repo.observeActiveRooms().first().isEmpty())
        repo.getActiveRooms()
        assertEquals(listOf("ABC123"), repo.observeActiveRooms().first().map { it.code })
    }

    @Test
    fun observeActiveRooms_leaveRemovesRoom_live() = runTest {
        val repo = newRepo(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/active-rooms") ->
                    respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_ONE_ROOM_JSON)
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        })
        repo.getActiveRooms()
        assertEquals(listOf("ABC123"), repo.observeActiveRooms().first().map { it.code })
        repo.leaveRoom("ABC123")
        assertTrue(
            repo.observeActiveRooms().first().isEmpty(),
            "a successful leave drops the room from the observed set with no re-fetch",
        )
    }

    @Test
    fun observeActiveRooms_joinUpsertsRoom_live() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, JOIN_RESPONSE_JSON_FRESH) })
        assertTrue(repo.observeActiveRooms().first().isEmpty())
        repo.joinRoom("ABC123")
        assertEquals(listOf("ABC123"), repo.observeActiveRooms().first().map { it.code })
    }

    @Test
    fun onForeground_warm_refreshesActiveRoomsFromServer() = runUnitTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_ONE_ROOM_JSON) })
        repo.observeActiveRooms().test {
            assertTrue(awaitItem().isEmpty())
            repo.onForeground(AppEvent.OnForeground(isColdBoot = false))
            assertEquals(
                listOf("ABC123"),
                awaitItem().map { it.code },
                "a warm foreground re-pulls the authoritative server snapshot so off-device changes surface",
            )
        }
    }

    @Test
    fun onForeground_coldBoot_doesNotRefresh() = runUnitTest {
        var calls = 0
        val repo = newRepo(MockEngine {
            calls++
            respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_ONE_ROOM_JSON)
        })
        repo.onForeground(AppEvent.OnForeground(isColdBoot = true))
        testScheduler.advanceUntilIdle()
        assertEquals(0, calls, "cold boot has its own load path; the listener must not double-fetch")
    }

    @Test
    fun onConnectivityRegained_refreshesActiveRoomsFromServer() = runUnitTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, ACTIVE_ROOMS_ONE_ROOM_JSON) })
        repo.observeActiveRooms().test {
            assertTrue(awaitItem().isEmpty())
            repo.onConnectivityRegained(AppEvent.ConnectivityRegained)
            assertEquals(
                listOf("ABC123"),
                awaitItem().map { it.code },
                "regaining connectivity re-pulls the server snapshot to catch changes made while offline",
            )
        }
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
        // The socket isn't exercised here — an empty handle keeps the
        // repo's connect delegation off the critical path.
        val socket = object : RoomSocket {
            override fun connect(code: String): RoomConnectionHandle = object : RoomConnectionHandle {
                override val connection: Flow<RoomConnection> = flow { }
                override val gameplayFrames: Flow<GameplayFrame> = flow { }
                override suspend fun send(frame: ClientFrame) = Unit
            }
        }
        return RoomRepositoryImpl(api, socket, AppCoroutineScope(dispatchers))
    }

    @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
    private class FakeNetworkClient(private val httpClient: HttpClient) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
        override suspend fun awaitAuthReady() = Unit
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
        private val OPEN_ROOM_JSON_HOST = """
            {
              "code":"ABC123",
              "hostUserId":"11111111-1111-1111-1111-111111111111",
              "createdAtEpochMs":1700000000000,
              "maxSeats":4,
              "status":"Lobby",
              "members":[],
              "visibility":"Open"
            }
        """.trimIndent()
        private val OPEN_ROOM_RESPONSE_JSON = """{"schemaVersion":1,"room":$OPEN_ROOM_JSON_HOST}"""
        private val UNKNOWN_VIS_ROOM_JSON = """
            {
              "code":"ABC123",
              "hostUserId":"11111111-1111-1111-1111-111111111111",
              "createdAtEpochMs":1700000000000,
              "maxSeats":4,
              "status":"Lobby",
              "members":[],
              "visibility":"FriendsOnly"
            }
        """.trimIndent()
        private val UNKNOWN_STATUS_ROOM_JSON = """
            {
              "code":"ABC123",
              "hostUserId":"11111111-1111-1111-1111-111111111111",
              "createdAtEpochMs":1700000000000,
              "maxSeats":4,
              "status":"Tournament",
              "members":[],
              "visibility":"Open"
            }
        """.trimIndent()
        private val JOIN_RESPONSE_JSON_FRESH =
            """{"schemaVersion":1,"alreadyJoined":false,"room":$ROOM_JSON_HOST}"""
        private val JOIN_RESPONSE_JSON_REJOIN =
            """{"schemaVersion":1,"alreadyJoined":true,"room":$ROOM_JSON_HOST}"""
        private val ACTIVE_ROOMS_ONE_ROOM_JSON =
            """{"schemaVersion":1,"rooms":[$ROOM_JSON_HOST]}"""
        private val ACTIVE_ROOMS_EMPTY_JSON =
            """{"schemaVersion":1,"rooms":[]}"""
    }
}
