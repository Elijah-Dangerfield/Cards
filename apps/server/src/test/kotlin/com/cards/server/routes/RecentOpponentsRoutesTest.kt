package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/**
 * Route-level tests for `GET /v1/recent-opponents`. The repository is faked so
 * the focus is the HTTP/JSON layer, JWT gating, and the limit query param; the
 * Postgres repo has its own integration tests.
 */
@OptIn(ExperimentalTime::class)
class RecentOpponentsRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val opponent = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @Test
    fun get_serializesIds_newestFirst() = runTest {
        val repo = FakeRecentOpponents(recent = listOf(UserId(opponent)))
        callGet(repo, "/v1/recent-opponents", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(opponent.toString()), resp.body<RecentOpponentsResponse>().opponents)
        }
    }

    @Test
    fun get_defaultsLimitTo10() = runTest {
        val repo = FakeRecentOpponents()
        callGet(repo, "/v1/recent-opponents", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(10, repo.lastLimit)
        }
    }

    @Test
    fun get_clampsLimitToMax() = runTest {
        val repo = FakeRecentOpponents()
        callGet(repo, "/v1/recent-opponents?limit=9999", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(50, repo.lastLimit)
        }
    }

    @Test
    fun get_honorsExplicitLimit() = runTest {
        val repo = FakeRecentOpponents()
        callGet(repo, "/v1/recent-opponents?limit=5", bearer = validJwt()) { resp ->
            assertEquals(5, repo.lastLimit)
        }
    }

    @Test
    fun get_returns401_whenUnauthenticated() = runTest {
        val repo = FakeRecentOpponents()
        callGet(repo, "/v1/recent-opponents", bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(-1, repo.lastLimit, "an unauthenticated caller never reaches the repo")
        }
    }

    // ---------- Test scaffolding ----------

    private class FakeRecentOpponents(
        private val recent: List<UserId> = emptyList(),
    ) : RecentOpponentsRepository {
        var lastLimit = -1

        override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId) = Unit

        override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> {
            lastLimit = limit
            return recent
        }

        override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean = false

        override suspend fun deleteAllForUser(userId: UserId) = Unit
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

    private suspend fun callGet(
        repo: RecentOpponentsRepository,
        path: String,
        bearer: String?,
        assert: suspend (HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { recentOpponentsRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get(path) {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(resp)
        }
    }
}
