package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.time.Instant

/**
 * Route-level tests for `GET /v1/me`. The repository is faked; we exercise
 * the HTTP-layer + JWT validation concerns end-to-end.
 *
 * Production verifies tokens via JWKS (asymmetric ES256). Tests mint
 * tokens we control with HS256 and pass a matching [JWTVerifier] to the
 * test-only `installAuthenticationWithVerifier` entry point — keeps tests
 * fast + hermetic (no JWKS fetch over the network).
 */
@OptIn(ExperimentalTime::class)
class MeRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun me_returnsProfile_whenJwtIsValid() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        callMe(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MeResponse>()
            assertEquals(userId.value.toString(), body.userId)
            assertEquals("FakeName", body.displayName)
            assertEquals("🦊", body.avatarEmoji)
        }
    }

    @Test
    fun me_createsProfile_onFirstContact() = runTest {
        val repo = FakeProfileRepository(existing = null)
        callMe(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, repo.findOrCreateCalls)
        }
    }

    @Test
    fun me_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeProfileRepository(existing = null)
        callMe(repo, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.findOrCreateCalls)
        }
    }

    @Test
    fun me_returns401_whenJwtSignedWithWrongSecret() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val foreign = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("wrong-secret-wrong-secret-wrong-secret-wrong-secret"))
        callMe(repo, bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenJwtIssuerIsWrong() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val foreign = JWT.create()
            .withIssuer("https://different-project.supabase.co/auth/v1")
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenJwtAudienceIsWrong() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val foreign = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("service_role")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenJwtIsExpired() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val expired = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withIssuedAt(Date(System.currentTimeMillis() - 120_000))
            .withExpiresAt(Date(System.currentTimeMillis() - 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = expired) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenSubIsNotAUuid() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val malformed = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject("not-a-uuid")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = malformed) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    // ---------- Test scaffolding ----------

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

    private suspend fun callMe(
        repo: ProfileRepository,
        bearer: String?,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { meRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.get("/v1/me") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(response)
        }
    }

    private fun fakeProfile(userId: UserId): Profile {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
        return Profile(
            userId = userId,
            displayName = "FakeName",
            avatarEmoji = "🦊",
            createdAt = now,
            updatedAt = now,
        )
    }

    private class FakeProfileRepository(private val existing: Profile?) : ProfileRepository {
        var findOrCreateCalls: Int = 0
            private set

        override suspend fun findById(userId: UserId): Profile? = existing

        override suspend fun findOrCreate(userId: UserId): Profile {
            findOrCreateCalls++
            return existing ?: Profile(
                userId = userId,
                displayName = "GeneratedName",
                avatarEmoji = "🦊",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            )
        }

        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
        ): com.dangerfield.cards.server.domain.UpdateProfileOutcome = error("not used in this test")
    }
}
