package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
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
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `GET /v1/profiles`. The repository is faked so the focus
 * is the HTTP/JSON layer, JWT gating, the comma-separated `ids` parsing, and the
 * unknown-id-omission contract; [com.dangerfield.cards.server.data.PostgresProfileRepository]
 * has its own integration tests for `findById`.
 */
@OptIn(ExperimentalTime::class)
class ProfilesRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val caller = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val a = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val b = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val unknown = UUID.fromString("44444444-4444-4444-4444-444444444444")

    @Test
    fun resolvesKnownIds() = runTest {
        val repo = repoWith(
            profile(a, "Patrice", "🦁", "#C658E4"),
            profile(b, "Jules", "🐙", null),
        )
        callGet(repo, "/v1/profiles?ids=$a,$b") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val profiles = resp.body<ProfilesResponse>().profiles
            assertEquals(2, profiles.size)
            val patrice = profiles.first { it.id == a.toString() }
            assertEquals("Patrice", patrice.displayName)
            assertEquals("🦁", patrice.avatarEmoji)
            assertEquals("#C658E4", patrice.avatarBackgroundColor)
            assertEquals(null, profiles.first { it.id == b.toString() }.avatarBackgroundColor)
        }
    }

    @Test
    fun omitsUnknownIds() = runTest {
        val repo = repoWith(profile(a, "Patrice", "🦁", null))
        callGet(repo, "/v1/profiles?ids=$a,$unknown") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val profiles = resp.body<ProfilesResponse>().profiles
            assertEquals(listOf(a.toString()), profiles.map { it.id })
        }
    }

    @Test
    fun emptyIds_returnsEmptyList() = runTest {
        val repo = repoWith()
        callGet(repo, "/v1/profiles") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.body<ProfilesResponse>().profiles.isEmpty())
            assertTrue(repo.queried.isEmpty(), "no ids means the repo is never touched")
        }
    }

    @Test
    fun deduplicatesIds_beforeResolving() = runTest {
        val repo = repoWith(profile(a, "Patrice", "🦁", null))
        callGet(repo, "/v1/profiles?ids=$a,$a,$a") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(a.toString()), resp.body<ProfilesResponse>().profiles.map { it.id })
            assertEquals(1, repo.queried.size, "a duplicated id resolves once")
        }
    }

    @Test
    fun malformedId_returns400() = runTest {
        val repo = repoWith(profile(a, "Patrice", "🦁", null))
        callGet(repo, "/v1/profiles?ids=$a,not-a-uuid") { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(repo.queried.isEmpty(), "a malformed batch never reaches the repo")
        }
    }

    @Test
    fun returns401_whenUnauthenticated() = runTest {
        val repo = repoWith(profile(a, "Patrice", "🦁", null))
        callGet(repo, "/v1/profiles?ids=$a", bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(repo.queried.isEmpty(), "an unauthenticated caller never reaches the repo")
        }
    }

    // ---------- Test scaffolding ----------

    private fun profile(id: UUID, name: String, emoji: String, bg: String?) = Profile(
        userId = UserId(id),
        displayName = name,
        avatarEmoji = emoji,
        avatarBackgroundColor = bg,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun repoWith(vararg profiles: Profile) =
        FakeProfileRepository(profiles.associateBy { it.userId })

    private class FakeProfileRepository(
        private val byId: Map<UserId, Profile>,
    ) : ProfileRepository {
        val queried = mutableListOf<UserId>()

        override suspend fun findById(userId: UserId): Profile? {
            queried += userId
            return byId[userId]
        }

        override suspend fun findOrCreate(userId: UserId): Profile = error("not used in this test")

        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("not used in this test")

        override suspend fun delete(userId: UserId) = Unit

        override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = null

        override suspend fun findInstallSiblings(
            installId: UUID,
            currentUserId: UserId,
        ): List<UserId> = emptyList()
    }

    private fun validJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(caller.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun callGet(
        repo: ProfileRepository,
        path: String,
        bearer: String? = "default",
        assert: suspend (HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { profilesRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val token = if (bearer == "default") validJwt() else bearer
            val resp = client.get(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(resp)
        }
    }
}
