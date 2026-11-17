package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.CreateStandingTableOutcome
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.StandingTableDraft
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import io.ktor.client.statement.HttpResponse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertFalse
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogLevel
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StandingTableRepositoryImplTest : CoroutineTest() {
    private val logs = RecordingRoomLogTree()

    @BeforeTest
    fun recordLogs() {
        KLog.plant(logs)
    }

    @AfterTest
    fun clearLogs() {
        KLog.clearTrees()
    }

    @Test
    fun malformedSuccessResponsesKeepMappingExceptionAndReportOneFailure() = runUnitTest {
        val repo = newRepo(MockEngine { respondJson(TABLE.replace("America/New_York", "Invalid/Zone")) })
        val result = repo.get("K7M2QX")
        assertTrue(result.isFailure)
        assertOperation("get", "failure", "decode")
        assertEquals(1, logs.entries.count { it.throwable === result.exceptionOrNull() })
        assertEquals(LogLevel.Error, logs.entries.single { it.throwable === result.exceptionOrNull() }.level)
        assertEquals(1, logs.events("standing_table.operation_completed").size)
    }

    @Test
    fun malformedEditResponseDoesNotRefreshAndPreservesDecodeFailure() = runUnitTest {
        val engine = MockEngine { respondJson("{") }
        val result = newRepo(engine).update("K7M2QX", draft())
        assertTrue(result.isFailure)
        assertOperation("update", "failure", "decode")
        assertEquals(1, logs.entries.count { it.throwable === result.exceptionOrNull() })
        assertEquals(LogLevel.Error, logs.entries.single { it.throwable === result.exceptionOrNull() }.level)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun everySuccessfulOperationEmitsExactlyOneCompletionIncludingSeparateRefreshes() = runUnitTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                request.url.encodedPath.endsWith("/mine") -> respondJson("""{"schemaVersion":1,"tables":[$TABLE]}""")
                else -> respondJson(TABLE)
            }
        }
        val repo = newRepo(engine)
        assertTrue(repo.refreshMine().isSuccess)
        assertTrue(repo.get("K7M2QX").isSuccess)
        assertIs<CreateStandingTableOutcome.Created>(repo.create(draft()))
        assertTrue(repo.update("K7M2QX", draft()).isSuccess)
        assertTrue(repo.archive("K7M2QX").isSuccess)
        assertTrue(repo.leave("K7M2QX").isSuccess)
        val events = logs.events("standing_table.operation_completed")
        assertEquals(mapOf("mine" to 5, "get" to 1, "create" to 1, "update" to 1, "archive" to 1, "leave" to 1),
            events.groupingBy { it.context.extras["operation"] as String }.eachCount())
        events.forEach { event ->
            assertEquals("success", event.context.extras["outcome"])
            assertEquals("none", event.context.extras["reason"])
            assertFalse(event.context.extras.keys.any { it in setOf("code", "name", "user_id") })
        }
    }

    @Test
    fun failedRefreshIsSeparateFromCommittedWriteAndDoesNotDoubleReportException() = runUnitTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/mine")) respondJson("{}", HttpStatusCode.ServiceUnavailable)
            else respondJson(TABLE)
        }
        assertIs<CreateStandingTableOutcome.Created>(newRepo(engine).create(draft()))
        assertOperation("create", "success", "none")
        assertOperation("mine", "failure", "server")
        assertEquals(1, logs.entries.count { it.throwable != null })
    }

    @Test
    fun rateLimitsAndServerFailuresHaveSeparateReasonsWithoutExtraExceptionCapture() = runUnitTest {
        listOf(
            HttpStatusCode.TooManyRequests to "rate_limited",
            HttpStatusCode.InternalServerError to "server",
            HttpStatusCode.BadGateway to "server",
            HttpStatusCode.Gone to "http",
        ).forEach { (status, reason) ->
            logs.entries.clear()
            val repo = newRepo(MockEngine { respondJson("{}", status) })
            assertTrue(repo.get("K7M2QX").isFailure)
            assertOperation("get", "failure", reason)
            assertEquals(1, logs.entries.count { it.throwable != null })
            assertTrue(logs.entries.none { it.level == LogLevel.Error })
        }
    }

    @Test
    fun failuresUseBoundedAuthOfflineValidationAndNetworkReasons() = runUnitTest {
        val unused = MockEngine { error("auth gate must not reach network") }
        assertTrue(newRepo(unused, AuthVerdict.Blocked(AuthReason.NeedAccount)).get("K7M2QX").isFailure)
        assertOperation("get", "failure", "auth")
        logs.entries.clear()
        assertTrue(newRepo(unused, AuthVerdict.Blocked(AuthReason.Offline)).get("K7M2QX").isFailure)
        assertOperation("get", "failure", "offline")
        logs.entries.clear()
        val invalid = newRepo(MockEngine { respondJson("""{"error":{"code":"bad_request","message":"name invalid"}}""", HttpStatusCode.BadRequest) })
        assertIs<CreateStandingTableOutcome.Invalid>(invalid.create(draft()))
        assertOperation("create", "failure", "invalid")
        assertEquals(1, logs.entries.count { it.throwable != null })
        logs.entries.clear()
        assertTrue(newRepo(MockEngine { throw IOException("connection refused") }).get("K7M2QX").isFailure)
        assertOperation("get", "failure", "network")
        assertEquals(1, logs.entries.count { it.throwable != null })
        logs.entries.clear()
        assertTrue(newRepo(MockEngine { throw HttpRequestTimeoutException("standing-tables", 1) }).get("K7M2QX").isFailure)
        assertOperation("get", "failure", "network")
    }

    @Test
    fun unexpectedClientExceptionIsUnknownRatherThanAnOutage() = runUnitTest {
        assertTrue(newRepo(MockEngine { error("client bug") }).get("K7M2QX").isFailure)
        assertOperation("get", "failure", "unknown")
    }

    @Test
    fun cancelledOperationPropagatesWithoutFailureEventOrExceptionCapture() = runUnitTest {
        val repo = newRepo(MockEngine { error("unused") }, decorateApi = { delegate ->
            object : StandingTableApi by delegate {
                override suspend fun get(code: String): HttpResponse = throw CancellationException("screen closed")
            }
        })
        assertFailsWith<CancellationException> { repo.get("K7M2QX") }
        assertTrue(logs.events("standing_table.operation_completed").isEmpty())
        assertTrue(logs.entries.none { it.throwable != null })
    }

    private fun assertOperation(operation: String, outcome: String, reason: String) {
        val entry = logs.events("standing_table.operation_completed").single { it.context.extras["operation"] == operation }
        assertEquals(outcome, entry.context.extras["outcome"])
        assertEquals(reason, entry.context.extras["reason"])
    }

    @Test
    fun createMapsOwnerLiveScheduleAndRefreshesMine() = runUnitTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) respondJson("""{"schemaVersion":1,"tables":[$TABLE]}""")
            else respondJson(TABLE, HttpStatusCode.Created)
        }
        val repo = newRepo(engine)
        val created = assertIs<CreateStandingTableOutcome.Created>(repo.create(draft()))
        assertEquals("K7M2QX", created.table.code)
        assertEquals(DayOfWeek.THURSDAY, created.table.weekday)
        assertEquals(LocalTime(20, 0), created.table.localTime)
        assertEquals("America/New_York", created.table.timeZone.id)
        assertEquals(RoomStatus.Playing, created.table.live?.status)
        assertEquals(3, created.table.live?.seatedHumans)
        assertTrue(created.table.isOwner)
        assertEquals(listOf(created.table), repo.mine.first())
        assertEquals(listOf("/v1/standing-tables", "/v1/standing-tables/mine"), engine.requestHistory.map { it.url.encodedPath })
    }

    @Test
    fun createMapsCapAndValidationSeparatelyFromNetworkFailures() = runUnitTest {
        val capped = newRepo(MockEngine { respondJson("""{"error":{"code":"too_many_standing_tables","message":"limit"}}""", HttpStatusCode.Conflict) })
        assertEquals(CreateStandingTableOutcome.TooMany, capped.create(draft()))
        val invalid = newRepo(MockEngine { respondJson("""{"error":{"code":"bad_request","message":"localTime must use quarter hours"}}""", HttpStatusCode.BadRequest) })
        assertEquals(CreateStandingTableOutcome.Invalid("localTime"), invalid.create(draft()))
        val offline = newRepo(MockEngine { error("offline") })
        assertEquals(CreateStandingTableOutcome.NetworkError, offline.create(draft()))
    }

    @Test
    fun leaveAndArchiveRefreshListAndUseDistinctPaths() = runUnitTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent)
            else respondJson("""{"schemaVersion":1,"tables":[]}""")
        }
        val repo = newRepo(engine)
        assertTrue(repo.leave("K7M2QX").isSuccess)
        assertTrue(repo.archive("K7M2QX").isSuccess)
        assertEquals(listOf("/v1/standing-tables/K7M2QX/regulars/me", "/v1/standing-tables/K7M2QX"), engine.requestHistory.filter { it.method == HttpMethod.Delete }.map { it.url.encodedPath })
    }

    @Test
    fun updateOmitsCosmeticsAndFormatsWireTimeAsHoursAndMinutes() = runUnitTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) respondJson("""{"schemaVersion":1,"tables":[$TABLE]}""")
            else respondJson(TABLE)
        }
        val repo = newRepo(engine)
        assertTrue(repo.update("K7M2QX", draft().copy(feltProductId = "felt_red", cardBackProductId = "back_blue")).isSuccess)
        val patch = engine.requestHistory.first()
        assertEquals(HttpMethod.Patch, patch.method)
        val body = (patch.body as TextContent).text
        assertTrue(body.contains("\"localTime\":\"20:00\""))
        assertTrue(!body.contains("feltProductId"))
        assertTrue(!body.contains("cardBackProductId"))
    }

    @Test
    fun failedRefreshRetainsPreviousMineAndFailedWriteDoesNotRefresh() = runUnitTest {
        var offline = false
        val engine = MockEngine {
            if (offline) error("offline")
            respondJson("""{"schemaVersion":1,"tables":[$TABLE]}""")
        }
        val repo = newRepo(engine)
        assertTrue(repo.refreshMine().isSuccess)
        offline = true
        assertTrue(repo.refreshMine().isFailure)
        assertEquals("K7M2QX", repo.mine.first().single().code)
        assertTrue(repo.archive("K7M2QX").isFailure)
    }

    @Test
    fun olderRefreshCannotResurrectArchivedTable() = runUnitTest {
        val olderRequestStarted = CompletableDeferred<Unit>()
        val releaseOlderResponse = CompletableDeferred<Unit>()
        var getCount = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Delete) {
                respond("", HttpStatusCode.NoContent)
            } else if (++getCount == 1) {
                olderRequestStarted.complete(Unit)
                releaseOlderResponse.await()
                respondJson("""{"schemaVersion":1,"tables":[$TABLE]}""")
            } else {
                respondJson("""{"schemaVersion":1,"tables":[]}""")
            }
        }
        val repo = newRepo(engine)
        val olderRefresh = async { repo.refreshMine() }
        olderRequestStarted.await()
        assertTrue(repo.archive("K7M2QX").isSuccess)
        releaseOlderResponse.complete(Unit)
        assertTrue(olderRefresh.await().isSuccess)
        assertEquals(emptyList(), repo.mine.first())
    }

    @Test
    fun roomDtoWithoutSummaryRemainsCompatibleAndWithSummaryMaps() {
        val json = Json { ignoreUnknownKeys = true }
        val room = """{"code":"K7M2QX","hostUserId":"owner","createdAtEpochMs":0,"maxSeats":6,"members":[],"futureField":true}"""
        assertEquals(null, json.decodeFromString<RoomDto>(room).toDomain().standingTable)
        val summary = """"standingTable":{"name":"Thursday Night Poker","weekday":4,"localTime":"20:00","timeZone":"America/New_York","nextStartsAt":"2026-11-20T01:00:00Z"}"""
        val decoded = json.decodeFromString<RoomDto>(room.dropLast(1) + "," + summary + "}").toDomain()
        assertEquals(DayOfWeek.THURSDAY, decoded.standingTable?.weekday)
    }

    private fun draft() = StandingTableDraft("Thursday Night Poker", 5000, 6, DayOfWeek.THURSDAY, LocalTime(20, 0), TimeZone.of("America/New_York"))

    private fun newRepo(
        engine: MockEngine,
        verdict: AuthVerdict = AuthVerdict.Ready,
        decorateApi: (StandingTableApi) -> StandingTableApi = { it },
    ): StandingTableRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        return StandingTableRepositoryImpl(decorateApi(HttpStandingTableApi(TestNetworkClient(client, verdict))), StandingTableCache())
    }

    @OptIn(InternalNetworkingApi::class)
    private class TestNetworkClient(private val httpClient: HttpClient, private val verdict: AuthVerdict) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
        override suspend fun awaitAuthReady() = Unit
        override suspend fun authVerdict(requirement: AuthRequirement) = verdict
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf("Content-Type", "application/json"))

    companion object {
        private const val TABLE = """{"schemaVersion":1,"code":"K7M2QX","name":"Thursday Night Poker","ownerUserId":"owner","ownerDisplayName":"Alex","isOwner":true,"buyIn":5000,"maxSeats":6,"weekday":4,"localTime":"20:00","timeZone":"America/New_York","durationMinutes":120,"nextStartsAt":"2026-11-20T01:00:00Z","regularCount":5,"live":{"seatedHumans":3,"status":"playing"},"archived":false,"feltProductId":null,"cardBackProductId":null}"""
    }
}
