package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.CandidatesOutcome
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [MatchmakingRepositoryImpl]'s HTTP-status → outcome mapping — the wire
 * contract the highest-traffic screen (the search) depends on. Mirrors
 * [RoomRepositoryImplTest]: the real [HttpMatchmakingApi] over a MockEngine, so
 * the assertions exercise the actual deserialization + interceptor path rather
 * than a hand-rolled fake.
 */
class MatchmakingRepositoryImplTest {

    // ---------- findTable ----------

    @Test
    fun findTable_200_joined_returnsSuccess_createdFalse() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, FIND_RESPONSE_JOINED) })
        val outcome = repo.findTable(minBuyIn = 1_000, maxBuyIn = 5_000)
        val success = assertIs<FindTableOutcome.Success>(outcome)
        assertEquals("ABC123", success.room.code)
        assertEquals(false, success.created, "seated into an existing table")
    }

    @Test
    fun findTable_200_created_returnsSuccess_createdTrue() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, FIND_RESPONSE_CREATED) })
        val outcome = repo.findTable(minBuyIn = 1_000, maxBuyIn = 5_000)
        val success = assertIs<FindTableOutcome.Success>(outcome)
        assertEquals(true, success.created, "opened a fresh table to wait in")
    }

    @Test
    fun findTable_400_returnsInvalidRange_withServerMessage() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.BadRequest,
                """{"error":{"code":"invalid_buy_in","message":"Buy-in range must be within 1000..100000."}}""",
            )
        })
        val invalid = assertIs<FindTableOutcome.InvalidRange>(repo.findTable(50, 50))
        assertTrue(invalid.message.contains("Buy-in"))
    }

    @Test
    fun findTable_401_returnsNotSignedIn() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<FindTableOutcome.NotSignedIn>(repo.findTable(1_000, 5_000))
    }

    @Test
    fun findTable_429_returnsRateLimited() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.TooManyRequests) })
        assertIs<FindTableOutcome.RateLimited>(repo.findTable(1_000, 5_000))
    }

    @Test
    fun findTable_500_returnsUnknown() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        assertIs<FindTableOutcome.Unknown>(repo.findTable(1_000, 5_000))
    }

    @Test
    fun findTable_transportError_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { throw SimulatedNetworkError("connection refused") })
        val outcome = repo.findTable(1_000, 5_000)
        val networkError = assertIs<FindTableOutcome.NetworkError>(outcome)
        assertTrue(networkError.cause is SimulatedNetworkError)
    }

    // ---------- playBots ----------

    @Test
    fun playBots_200_returnsSuccess_withRoom() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, FIND_RESPONSE_JOINED) })
        val success = assertIs<PlayBotsOutcome.Success>(repo.playBots("ABC123"))
        assertEquals("ABC123", success.room.code)
    }

    @Test
    fun playBots_409_realPlayerPresent_returnsRealPlayerJoined() = runTest {
        // The happy surprise: a human arrived mid-search, so the server kept the
        // real game. This must NOT read as an error.
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.Conflict,
                """{"error":{"code":"real_player_present","message":"A real player joined — playing them instead."}}""",
            )
        })
        assertIs<PlayBotsOutcome.RealPlayerJoined>(repo.playBots("ABC123"))
    }

    @Test
    fun playBots_409_otherCode_returnsUnknown() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.Conflict,
                """{"error":{"code":"not_public","message":"Bot fallback is only for public tables."}}""",
            )
        })
        assertIs<PlayBotsOutcome.Unknown>(repo.playBots("ABC123"))
    }

    @Test
    fun playBots_404_returnsRoomNotFound() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.NotFound) })
        assertIs<PlayBotsOutcome.RoomNotFound>(repo.playBots("ABC123"))
    }

    @Test
    fun playBots_401_returnsNotSignedIn() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<PlayBotsOutcome.NotSignedIn>(repo.playBots("ABC123"))
    }

    @Test
    fun playBots_transportError_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { throw SimulatedNetworkError("dns") })
        val outcome = repo.playBots("ABC123")
        val networkError = assertIs<PlayBotsOutcome.NetworkError>(outcome)
        assertTrue(networkError.cause is SimulatedNetworkError)
    }

    // ---------- candidates ----------

    @Test
    fun candidates_200_returnsRooms_inOrder() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, CANDIDATES_RESPONSE) })
        val success = assertIs<CandidatesOutcome.Success>(repo.findCandidates(1_000, 5_000))
        assertEquals(listOf("AAA111", "BBB222"), success.rooms.map { it.code }, "server order preserved")
    }

    @Test
    fun candidates_200_empty_returnsEmptySuccess() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, """{"schemaVersion":1,"rooms":[]}""") })
        val success = assertIs<CandidatesOutcome.Success>(repo.findCandidates(1_000, 5_000))
        assertTrue(success.rooms.isEmpty(), "no match → empty list, the caller offers bots")
    }

    @Test
    fun candidates_400_insufficientBalance_returnsInsufficient() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.BadRequest,
                """{"error":{"code":"insufficient_balance","message":"That buy-in is more than your balance of 100 chips."}}""",
            )
        })
        assertIs<CandidatesOutcome.InsufficientBalance>(repo.findCandidates(1_000, 5_000))
    }

    @Test
    fun candidates_400_returnsInvalidRange() = runTest {
        val repo = newRepo(MockEngine {
            respondJson(
                HttpStatusCode.BadRequest,
                """{"error":{"code":"invalid_range","message":"minBuyIn must be <= maxBuyIn."}}""",
            )
        })
        assertIs<CandidatesOutcome.InvalidRange>(repo.findCandidates(5_000, 1_000))
    }

    @Test
    fun candidates_401_returnsNotSignedIn() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<CandidatesOutcome.NotSignedIn>(repo.findCandidates(1_000, 5_000))
    }

    // ---------- scaffolding ----------

    private class SimulatedNetworkError(message: String) : RuntimeException(message)

    private fun newRepo(engine: MockEngine): MatchmakingRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            expectSuccess = true
        }
        return MatchmakingRepositoryImpl(HttpMatchmakingApi(FakeNetworkClient(client)))
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
        private val ROOM_JSON = """
            {
              "code":"ABC123",
              "hostUserId":"00000000-0000-0000-0000-000000000000",
              "createdAtEpochMs":1700000000000,
              "maxSeats":6,
              "status":"Lobby",
              "members":[]
            }
        """.trimIndent()
        private val FIND_RESPONSE_JOINED = """{"schemaVersion":1,"created":false,"room":$ROOM_JSON}"""
        private val FIND_RESPONSE_CREATED = """{"schemaVersion":1,"created":true,"room":$ROOM_JSON}"""
        private fun roomJson(code: String) = ROOM_JSON.replace("\"ABC123\"", "\"$code\"")
        private val CANDIDATES_RESPONSE =
            """{"schemaVersion":1,"rooms":[${roomJson("AAA111")},${roomJson("BBB222")}]}"""
    }
}
