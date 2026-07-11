package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.EarnedAchievement
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
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
 * Route-level tests for `POST /v1/me/achievements/sync`, focused on the
 * ENG-9 chip-reward credit: recording a chip-mapped single-player
 * achievement mints the server-owned amount, idempotently, and unmapped
 * ids mint nothing.
 */
@OptIn(ExperimentalTime::class)
class AchievementsRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("44444444-4444-4444-4444-444444444444"))

    @Test
    fun sync_creditsServerOwnedChips_forChipMappedAchievement() = runTest {
        val wallet = RecordingWalletRepo()
        callSync(
            wallet = wallet,
            request = AchievementsSyncRequest(
                earned = listOf(EarnedAchievementDto(achievementId = "POT_5000", earnedAtEpochMs = 1_000)),
            ),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val grant = wallet.applies.single()
            assertEquals("achievement:POT_5000", grant.idempotencyKey)
            assertEquals(500L, grant.delta)
            assertEquals("achievement_grant:POT_5000", grant.reason)
            assertEquals(
                Wallet.STARTER_GRANT + 500L,
                resp.body<AchievementsSyncResponse>().walletBalance,
                "a mint carries the post-mint balance so the client re-pulls its wallet (PROG-12)",
            )
        }
    }

    @Test
    fun sync_replay_doesNotDoubleCredit() = runTest {
        val wallet = RecordingWalletRepo()
        val achievements = FakeAchievementsRepo()
        val request = AchievementsSyncRequest(
            earned = listOf(EarnedAchievementDto(achievementId = "TRIPLE_UP", earnedAtEpochMs = 1_000)),
        )
        callSync(wallet = wallet, achievements = achievements, request = request) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        callSync(wallet = wallet, achievements = achievements, request = request) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, wallet.applies.size, "replaying the earned id must not mint twice")
            assertEquals(250L, wallet.applies.single().delta)
            assertEquals(
                null,
                resp.body<AchievementsSyncResponse>().walletBalance,
                "a replay minted nothing — no re-pull signal",
            )
        }
    }

    @Test
    fun sync_grantsNothing_forAchievementsWithoutChipRewards() = runTest {
        val wallet = RecordingWalletRepo()
        callSync(
            wallet = wallet,
            request = AchievementsSyncRequest(
                earned = listOf(
                    EarnedAchievementDto(achievementId = "FIRST_HAND", earnedAtEpochMs = 1_000),
                    // MP ids are server-witnessed — their chips come from
                    // DefaultServerWitnessedAchievements, never from here.
                    EarnedAchievementDto(achievementId = "TRIPLE_UP_MP", earnedAtEpochMs = 1_000),
                ),
            ),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(wallet.applies.isEmpty())
            assertEquals(null, resp.body<AchievementsSyncResponse>().walletBalance, "no mint, no wallet signal")
        }
    }

    // ---------- scaffolding ----------

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
        wallet: RecordingWalletRepo,
        request: AchievementsSyncRequest,
        achievements: AchievementRepository = FakeAchievementsRepo(),
        bearer: String? = validJwt(),
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { achievementsRoutes(achievements, wallet) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.post("/v1/me/achievements/sync") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assert(resp)
        }
    }

    private class FakeAchievementsRepo : AchievementRepository {
        private val earned = mutableMapOf<String, EarnedAchievement>()

        override suspend fun recordEarned(
            userId: UserId,
            achievementId: String,
            earnedAt: Instant,
        ): EarnedAchievement = earned.getOrPut(achievementId) {
            EarnedAchievement(achievementId = achievementId, earnedAt = earnedAt)
        }

        override suspend fun listEarned(userId: UserId): List<EarnedAchievement> = earned.values.toList()

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }
}
