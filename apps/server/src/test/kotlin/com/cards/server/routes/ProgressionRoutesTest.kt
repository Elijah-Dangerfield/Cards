package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.FindOrCreateProgressionResult
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserProgression
import com.dangerfield.cards.server.domain.XpEvent
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `POST /v1/me/progression/sync`, focused on the
 * recent-XP re-hydration echo. The repository is faked so the test exercises
 * the HTTP/JSON layer + the "only echo on a pure-hydrate sync" gate; the
 * Postgres repo + its `recentEvents` query have their own integration test.
 */
@OptIn(ExperimentalTime::class)
class ProgressionRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))

    @Test
    fun sync_withNoEvents_echoesRecentLedgerForRehydration() = runTest {
        val repo = FakeProgressionRepo(
            recent = listOf(
                xpEvent("k1", deltaXp = 40, source = "hand", mode = "MULTIPLAYER", handId = "h1", at = 1_000),
                xpEvent("k2", deltaXp = 2_000, source = "achievement", mode = "BOTS", handId = null, at = 500),
            ),
        )
        callSync(repo, ProgressionSyncRequest(events = emptyList())) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<ProgressionSyncResponse>()
            assertEquals(2, body.recentEvents.size)
            val first = body.recentEvents.first()
            assertEquals("k1", first.idempotencyKey)
            assertEquals(40, first.deltaXp)
            assertEquals("MULTIPLAYER", first.mode)
            assertEquals("h1", first.handId)
            assertEquals(1_000, first.appliedAtEpochMs)
        }
    }

    @Test
    fun sync_withEvents_doesNotEchoRecentLedger() = runTest {
        // Steady-state sync (the client still holds these rows locally) must
        // not re-ship them — keeps the hot path's payload lean.
        val repo = FakeProgressionRepo(
            recent = listOf(xpEvent("k1", deltaXp = 40, source = "hand", mode = "BOTS", handId = "h1", at = 1)),
        )
        callSync(
            repo,
            ProgressionSyncRequest(events = listOf(XpEventDto("new", deltaXp = 10, source = "hand", mode = "BOTS"))),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<ProgressionSyncResponse>()
            assertTrue(body.recentEvents.isEmpty(), "events-carrying sync must not echo recentEvents")
            assertEquals(0, repo.recentEventsCalls, "recentEvents must not even be queried on a steady-state sync")
        }
    }

    @Test
    fun sync_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeProgressionRepo(recent = emptyList())
        callSync(repo, ProgressionSyncRequest(events = emptyList()), bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    // ---------- scaffolding ----------

    private fun xpEvent(
        key: String,
        deltaXp: Long,
        source: String,
        mode: String,
        handId: String?,
        at: Long,
        wasBoosted: Boolean = false,
    ): XpEvent = XpEvent(
        userId = userId,
        idempotencyKey = key,
        deltaXp = deltaXp,
        source = source,
        mode = mode,
        handId = handId,
        wasBoosted = wasBoosted,
        appliedAt = Instant.fromEpochMilliseconds(at),
    )

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

    private suspend fun callSync(
        repo: ProgressionRepository,
        request: ProgressionSyncRequest,
        bearer: String? = validJwt(),
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { progressionRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.post("/v1/me/progression/sync") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assert(resp)
        }
    }

    private class FakeProgressionRepo(
        private val recent: List<XpEvent>,
    ) : ProgressionRepository {
        var recentEventsCalls: Int = 0
            private set

        private var total: Long = 0

        override suspend fun findOrCreateResult(userId: UserId): FindOrCreateProgressionResult =
            FindOrCreateProgressionResult(
                progression = UserProgression(
                    userId = userId,
                    totalXp = total,
                    createdAt = Instant.fromEpochMilliseconds(0),
                    updatedAt = Instant.fromEpochMilliseconds(0),
                ),
                created = false,
            )

        override suspend fun find(userId: UserId): UserProgression? = null

        override suspend fun applyXp(
            userId: UserId,
            idempotencyKey: String,
            deltaXp: Long,
            source: String,
            mode: String,
            handId: String?,
            wasBoosted: Boolean,
        ): ApplyXpOutcome {
            total += deltaXp
            return ApplyXpOutcome.Applied(totalXp = total, wasAlreadyApplied = false)
        }

        override suspend fun recentEvents(userId: UserId, limit: Int): List<XpEvent> {
            recentEventsCalls++
            return recent.take(limit)
        }

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }
}
