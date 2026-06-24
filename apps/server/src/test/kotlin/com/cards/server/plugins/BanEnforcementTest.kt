package com.dangerfield.cards.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.BanReason
import com.dangerfield.cards.server.domain.BanStatus
import com.dangerfield.cards.server.domain.ModerationRepository
import com.dangerfield.cards.server.domain.UserId
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Plugin-level tests for [BanEnforcement]. Mounts a trivial authenticated
 * route guarded by the plugin and a fake [ModerationRepository], so the
 * concern under test is "does a banned caller get a typed 403 instead of
 * reaching the handler" — without a DB or the full route graph.
 *
 * Tokens are minted with HS256 + a known secret and verified against a
 * matching [com.auth0.jwt.interfaces.JWTVerifier], mirroring [MeRoutesTest].
 */
@OptIn(ExperimentalTime::class)
class BanEnforcementTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun allowsThrough_whenCallerInGoodStanding() = runTest {
        callGuarded(FakeModeration(status = null), bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("ok", resp.bodyAsText())
        }
    }

    @Test
    fun returns403WithReason_whenCallerIsBanned() = runTest {
        val until = Instant.parse("2030-01-01T00:00:00Z")
        callGuarded(
            FakeModeration(status = BanStatus(BanReason.Banned, until)),
            bearer = validJwt(),
            appealUrl = "https://cards.app/appeal",
        ) { resp ->
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            val body = resp.body<AccessDeniedResponse>()
            assertEquals("banned", body.reason)
            assertEquals(until.toString(), body.until)
            assertEquals("https://cards.app/appeal", body.appealUrl)
        }
    }

    @Test
    fun omitsAppealUrl_whenServerHasNoneConfigured() = runTest {
        callGuarded(
            FakeModeration(status = BanStatus(BanReason.Banned, until = null)),
            bearer = validJwt(),
            appealUrl = null,
        ) { resp ->
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            val body = resp.body<AccessDeniedResponse>()
            assertEquals("banned", body.reason)
            assertNull(body.until)
            assertNull(body.appealUrl)
        }
    }

    @Test
    fun failsOpen_whenLookupThrows() = runTest {
        callGuarded(FakeModeration(throwOnLookup = true), bearer = validJwt()) { resp ->
            // A transient lookup failure must not lock the user out — the
            // request proceeds and the token expires on its own schedule.
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun doesNotLookUp_whenUnauthenticated() = runTest {
        val moderation = FakeModeration(status = BanStatus(BanReason.Banned, until = null))
        callGuarded(moderation, bearer = null) { resp ->
            // No principal → the auth block 401s and the plugin never runs.
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, moderation.lookups)
        }
    }

    private suspend fun callGuarded(
        moderation: ModerationRepository,
        bearer: String?,
        appealUrl: String? = null,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthentication(
                    verification = JwtVerification.Static(testVerifier),
                    banGate = BanGate(moderation = moderation, appealUrl = appealUrl),
                )
                routing {
                    authenticate(SUPABASE_JWT_AUTH) {
                        get("/guarded") { call.respondText("ok") }
                    }
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.get("/guarded") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(response)
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

    private class FakeModeration(
        private val status: BanStatus? = null,
        private val throwOnLookup: Boolean = false,
    ) : ModerationRepository {
        var lookups = 0
            private set

        override suspend fun banStatusFor(userId: UserId): BanStatus? {
            lookups++
            if (throwOnLookup) error("simulated DB hiccup")
            return status
        }
    }
}
