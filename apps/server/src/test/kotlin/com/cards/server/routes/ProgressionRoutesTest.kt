package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.FindOrCreateProgressionResult
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserProgression
import com.dangerfield.cards.server.domain.Wallet
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

    @Test
    fun sync_creditsLevelChips_whenXpCrossesARewardedLevel() = runTest {
        // ENG-9: level-up chips are minted here, off the authoritative XP
        // total — not from a client-asserted wallet event. 600 XP under the
        // default curve is level 3 (cumulative 100 + 400 = 500), the first
        // rewarded level.
        val repo = FakeProgressionRepo(recent = emptyList())
        val wallet = RecordingWalletRepo()
        callSync(
            repo,
            ProgressionSyncRequest(events = listOf(XpEventDto("h1", deltaXp = 600, source = "hand", mode = "BOTS"))),
            wallet = wallet,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, wallet.applies.size)
            val grant = wallet.applies.single()
            assertEquals("levelup:3", grant.idempotencyKey)
            assertEquals(1_000L, grant.delta)
            assertEquals("levelup_grant:3", grant.reason)
            assertEquals(
                Wallet.STARTER_GRANT + 1_000L,
                resp.body<ProgressionSyncResponse>().walletBalance,
                "a mint carries the post-mint balance so the client re-pulls its wallet (PROG-12)",
            )
        }
    }

    @Test
    fun sync_creditsEveryRewardedLevel_whenABatchCrossesSeveral() = runTest {
        // 3_000 XP lands exactly at level 5 (100 + 400 + 900 + 1_600) —
        // levels 3 and 5 are both rewarded, level 4 grants nothing.
        val repo = FakeProgressionRepo(recent = emptyList())
        val wallet = RecordingWalletRepo()
        callSync(
            repo,
            ProgressionSyncRequest(events = listOf(XpEventDto("h1", deltaXp = 3_000, source = "hand", mode = "BOTS"))),
            wallet = wallet,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf("levelup:3", "levelup:5"), wallet.applies.map { it.idempotencyKey })
            assertEquals(listOf(1_000L, 2_500L), wallet.applies.map { it.delta })
            assertEquals(
                Wallet.STARTER_GRANT + 3_500L,
                resp.body<ProgressionSyncResponse>().walletBalance,
                "the balance after the last crossing rides the response",
            )
        }
    }

    @Test
    fun sync_grantsNothing_whenNoLevelCrossed() = runTest {
        val repo = FakeProgressionRepo(recent = emptyList())
        val wallet = RecordingWalletRepo()
        callSync(
            repo,
            ProgressionSyncRequest(events = listOf(XpEventDto("h1", deltaXp = 40, source = "hand", mode = "BOTS"))),
            wallet = wallet,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(wallet.applies.isEmpty(), "no rewarded level crossed, no chips minted")
            assertEquals(
                null,
                resp.body<ProgressionSyncResponse>().walletBalance,
                "no mint, no wallet signal",
            )
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
        wallet: RecordingWalletRepo = RecordingWalletRepo(),
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { progressionRoutes(repo, wallet) }
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
