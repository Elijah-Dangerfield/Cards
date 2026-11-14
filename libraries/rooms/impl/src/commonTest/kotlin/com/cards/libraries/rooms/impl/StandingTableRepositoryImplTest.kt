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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StandingTableRepositoryImplTest : CoroutineTest() {
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

    private fun newRepo(engine: MockEngine): StandingTableRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        return StandingTableRepositoryImpl(HttpStandingTableApi(TestNetworkClient(client)), StandingTableCache())
    }

    @OptIn(InternalNetworkingApi::class)
    private class TestNetworkClient(private val httpClient: HttpClient) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
        override suspend fun awaitAuthReady() = Unit
        override suspend fun authVerdict(requirement: AuthRequirement) = AuthVerdict.Ready
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf("Content-Type", "application/json"))

    companion object {
        private const val TABLE = """{"schemaVersion":1,"code":"K7M2QX","name":"Thursday Night Poker","ownerUserId":"owner","ownerDisplayName":"Alex","isOwner":true,"buyIn":5000,"maxSeats":6,"weekday":4,"localTime":"20:00","timeZone":"America/New_York","durationMinutes":120,"nextStartsAt":"2026-11-20T01:00:00Z","regularCount":5,"live":{"seatedHumans":3,"status":"playing"},"archived":false,"feltProductId":null,"cardBackProductId":null}"""
    }
}
