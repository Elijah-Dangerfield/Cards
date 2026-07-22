package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.CandidatesOutcome
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import com.dangerfield.cards.libraries.rooms.SubsidyBudgetOutcome
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
    fun findTable_raw401_returnsNetworkError() = runTest {
        // Post-gate, a raw 401 is a transient refresh failure — connectivity,
        // not account. Confirmed account problems arrive as typed AuthUnready.
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<FindTableOutcome.NetworkError>(repo.findTable(1_000, 5_000))
    }

    @Test
    fun findTable_authUnready_needAccount_returnsNotSignedIn() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val repo = newRepo(engine, verdict = AuthVerdict.Blocked(AuthReason.NeedAccount))
        assertIs<FindTableOutcome.NotSignedIn>(repo.findTable(1_000, 5_000))
        assertTrue(engine.requestHistory.isEmpty(), "a blocked call must never reach the wire")
    }

    @Test
    fun findTable_authUnready_offline_returnsNetworkError() = runTest {
        val repo = newRepo(
            MockEngine { respondError(HttpStatusCode.InternalServerError) },
            verdict = AuthVerdict.Blocked(AuthReason.Offline),
        )
        assertIs<FindTableOutcome.NetworkError>(repo.findTable(1_000, 5_000))
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
    fun playBots_raw401_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<PlayBotsOutcome.NetworkError>(repo.playBots("ABC123"))
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
    fun candidates_200_returnsRooms_inOrder_withAffordabilityFlags() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, CANDIDATES_RESPONSE) })
        val success = assertIs<CandidatesOutcome.Success>(repo.findCandidates(1_000, 5_000))
        assertEquals(listOf("AAA111", "BBB222"), success.rooms.map { it.room.code }, "server order preserved")
        // The first is affordable, the second unaffordable with its needed balance —
        // the client renders "need X chips" straight from the server, no 4× math.
        assertEquals(listOf(true, false), success.rooms.map { it.affordable })
        assertEquals(100_000, success.rooms[1].minBalanceToSit)
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
    fun candidates_raw401_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<CandidatesOutcome.NetworkError>(repo.findCandidates(1_000, 5_000))
    }

    // ---------- subsidyBudget ----------

    @Test
    fun subsidyBudget_200_returnsSuccess_withFields() = runTest {
        val repo = newRepo(MockEngine { respondJson(HttpStatusCode.OK, SUBSIDY_BUDGET_PARTIAL) })
        val success = assertIs<SubsidyBudgetOutcome.Success>(repo.subsidyBudget())
        assertEquals(8_500, success.grantedToday)
        assertEquals(10_000, success.cap)
        assertEquals(1_500, success.remaining)
    }

    @Test
    fun subsidyBudget_raw401_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertIs<SubsidyBudgetOutcome.NetworkError>(repo.subsidyBudget())
    }

    @Test
    fun subsidyBudget_500_returnsUnknown() = runTest {
        val repo = newRepo(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        assertIs<SubsidyBudgetOutcome.Unknown>(repo.subsidyBudget())
    }

    @Test
    fun subsidyBudget_transportError_returnsNetworkError() = runTest {
        val repo = newRepo(MockEngine { throw SimulatedNetworkError("offline") })
        val outcome = repo.subsidyBudget()
        val networkError = assertIs<SubsidyBudgetOutcome.NetworkError>(outcome)
        assertTrue(networkError.cause is SimulatedNetworkError)
    }

    // ---------- scaffolding ----------

    private class SimulatedNetworkError(message: String) : RuntimeException(message)

    private fun newRepo(
        engine: MockEngine,
        verdict: AuthVerdict = AuthVerdict.Ready,
    ): MatchmakingRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            expectSuccess = true
        }
        return MatchmakingRepositoryImpl(HttpMatchmakingApi(FakeNetworkClient(client, verdict)))
    }

    @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
    private class FakeNetworkClient(
        private val httpClient: HttpClient,
        private val verdict: AuthVerdict = AuthVerdict.Ready,
    ) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
        override suspend fun awaitAuthReady() = Unit
        override suspend fun authVerdict(requirement: AuthRequirement): AuthVerdict = verdict
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
        private fun candidateJson(code: String, affordable: Boolean, minBalanceToSit: Long) =
            """{"room":${roomJson(code)},"affordable":$affordable,"minBalanceToSit":$minBalanceToSit}"""
        private val CANDIDATES_RESPONSE =
            """{"schemaVersion":1,"rooms":[""" +
                "${candidateJson("AAA111", affordable = true, minBalanceToSit = 4_000)}," +
                candidateJson("BBB222", affordable = false, minBalanceToSit = 100_000) +
                "]}"
        private val SUBSIDY_BUDGET_PARTIAL =
            """{"schemaVersion":1,"grantedToday":8500,"cap":10000,"remaining":1500}"""
    }
}
