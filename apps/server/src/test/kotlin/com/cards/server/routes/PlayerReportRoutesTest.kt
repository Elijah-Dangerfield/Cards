package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.PlayerReportRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Route-level tests for the player-report endpoint. The repository is faked so
 * the focus is the HTTP/JSON layer, JWT gating, and the validation branches;
 * the Postgres repo has its own integration test.
 */
class PlayerReportRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val other = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @Test
    fun report_records_andReturnsReceived() = runTest {
        val repo = FakeReportRepo()
        callPost(
            repo,
            body = """{"reportedUserId":"$other","roomCode":"ABCD","reason":"offensive name"}""",
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("received", resp.body<PlayerReportResult>().status)
            assertEquals(userId to other, repo.lastReporterAndReported)
            assertEquals("ABCD", repo.lastRoom)
            assertEquals("offensive name", repo.lastReason)
        }
    }

    @Test
    fun report_blankRoomAndReason_normalizeToNull() = runTest {
        val repo = FakeReportRepo()
        callPost(
            repo,
            body = """{"reportedUserId":"$other","roomCode":"   ","reason":""}""",
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertNull(repo.lastRoom)
            assertNull(repo.lastReason)
        }
    }

    @Test
    fun report_missingContext_isAccepted() = runTest {
        val repo = FakeReportRepo()
        callPost(repo, body = """{"reportedUserId":"$other"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(userId to other, repo.lastReporterAndReported)
        }
    }

    @Test
    fun report_returns400_onSelfReport() = runTest {
        val repo = FakeReportRepo()
        callPost(repo, body = """{"reportedUserId":"${userId.value}"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, repo.recordCalls, "a self-report never reaches the repo")
        }
    }

    @Test
    fun report_returns400_onInvalidUserId() = runTest {
        val repo = FakeReportRepo()
        callPost(repo, body = """{"reportedUserId":"not-a-uuid"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, repo.recordCalls, "a malformed id never reaches the repo")
        }
    }

    @Test
    fun report_returns401_whenUnauthenticated() = runTest {
        val repo = FakeReportRepo()
        callPost(repo, body = """{"reportedUserId":"$other"}""", bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.recordCalls)
        }
    }

    // ---------- Test scaffolding ----------

    private class FakeReportRepo : PlayerReportRepository {
        var recordCalls = 0
        var lastReporterAndReported: Pair<UserId, UUID>? = null
        var lastRoom: String? = null
        var lastReason: String? = null

        override suspend fun record(reporter: UserId, reported: UserId, inRoom: String?, reason: String?) {
            recordCalls++
            lastReporterAndReported = reporter to reported.value
            lastRoom = inRoom
            lastReason = reason
        }
    }

    private fun validJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(userId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun callPost(
        repo: PlayerReportRepository,
        body: String,
        bearer: String?,
        assert: suspend (HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { playerReportRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.post("/v1/reports") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assert(resp)
        }
    }
}
