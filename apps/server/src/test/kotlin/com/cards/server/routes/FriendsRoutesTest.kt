package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import kotlin.time.ExperimentalTime

/**
 * Route-level tests for the friend-graph endpoints. The repository is faked so
 * the focus is the HTTP/JSON layer, JWT gating, and the outcome→status
 * mapping; the Postgres repo has its own integration tests.
 */
@OptIn(ExperimentalTime::class)
class FriendsRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val other = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @Test
    fun sendRequest_returnsRequested_onSent() = runTest {
        val repo = FakeFriendRepo(sendResult = SendRequestResult.Sent)
        callPost(repo, "/v1/friends/requests", body = """{"userId":"$other"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("requested", resp.body<FriendRequestResult>().state)
            assertEquals(userId to other, repo.lastSend)
        }
    }

    @Test
    fun sendRequest_returnsAccepted_onMutualRequest() = runTest {
        val repo = FakeFriendRepo(sendResult = SendRequestResult.NowFriends)
        callPost(repo, "/v1/friends/requests", body = """{"userId":"$other"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("accepted", resp.body<FriendRequestResult>().state)
        }
    }

    @Test
    fun sendRequest_returns403_whenBlocked() = runTest {
        val repo = FakeFriendRepo(sendResult = SendRequestResult.Blocked)
        callPost(repo, "/v1/friends/requests", body = """{"userId":"$other"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }
    }

    @Test
    fun sendRequest_returns400_onSelfRequest() = runTest {
        val repo = FakeFriendRepo(sendResult = SendRequestResult.SelfRequest)
        // The self-request body carries the caller's own id; the gate is skipped
        // for self so the clearer 400 still surfaces even with no played-with row.
        callPost(
            repo,
            "/v1/friends/requests",
            body = """{"userId":"${userId.value}"}""",
            bearer = validJwt(),
            recent = FakeRecentOpponents(hasPlayed = false),
        ) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun sendRequest_returns403_whenNotPlayedWith() = runTest {
        val repo = FakeFriendRepo(sendResult = SendRequestResult.Sent)
        callPost(
            repo,
            "/v1/friends/requests",
            body = """{"userId":"$other"}""",
            bearer = validJwt(),
            recent = FakeRecentOpponents(hasPlayed = false),
        ) { resp ->
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            assertEquals(0, repo.sendCalls, "a stranger never reaches the friend repo")
        }
    }

    @Test
    fun sendRequest_succeeds_whenPlayedWith() = runTest {
        val repo = FakeFriendRepo(sendResult = SendRequestResult.Sent)
        callPost(
            repo,
            "/v1/friends/requests",
            body = """{"userId":"$other"}""",
            bearer = validJwt(),
            recent = FakeRecentOpponents(hasPlayed = true),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(userId to other, repo.lastSend)
        }
    }

    @Test
    fun sendRequest_returns400_onInvalidUserId() = runTest {
        val repo = FakeFriendRepo()
        callPost(repo, "/v1/friends/requests", body = """{"userId":"not-a-uuid"}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, repo.sendCalls, "a malformed id never reaches the repo")
        }
    }

    @Test
    fun sendRequest_returns401_whenUnauthenticated() = runTest {
        val repo = FakeFriendRepo()
        callPost(repo, "/v1/friends/requests", body = """{"userId":"$other"}""", bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.sendCalls)
        }
    }

    @Test
    fun accept_mapsOutcomesToStatus() = runTest {
        callPost(FakeFriendRepo(respondResult = RespondResult.Ok), "/v1/friends/requests/$other/accept", bearer = validJwt()) {
            assertEquals(HttpStatusCode.OK, it.status)
        }
        callPost(FakeFriendRepo(respondResult = RespondResult.NotFound), "/v1/friends/requests/$other/accept", bearer = validJwt()) {
            assertEquals(HttpStatusCode.NotFound, it.status)
        }
        callPost(FakeFriendRepo(respondResult = RespondResult.Forbidden), "/v1/friends/requests/$other/accept", bearer = validJwt()) {
            assertEquals(HttpStatusCode.Forbidden, it.status)
        }
    }

    @Test
    fun decline_routesToDecline() = runTest {
        val repo = FakeFriendRepo(respondResult = RespondResult.Ok)
        callPost(repo, "/v1/friends/requests/$other/decline", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("decline", repo.lastRespondAction)
        }
    }

    @Test
    fun block_alwaysSucceeds() = runTest {
        val repo = FakeFriendRepo()
        callPost(repo, "/v1/friends/requests/$other/block", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("blocked", resp.body<FriendRequestResult>().state)
            assertEquals(userId.value to other, repo.lastBlock)
        }
    }

    @Test
    fun accept_returns400_onInvalidPathId() = runTest {
        val repo = FakeFriendRepo()
        callPost(repo, "/v1/friends/requests/not-a-uuid/accept", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun getFriends_serializesIds() = runTest {
        val repo = FakeFriendRepo(friends = listOf(UserId(other)))
        callGet(repo, "/v1/friends", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(other.toString()), resp.body<FriendsResponse>().friends)
        }
    }

    @Test
    fun getRequests_serializesIds() = runTest {
        val repo = FakeFriendRepo(incoming = listOf(UserId(other)))
        callGet(repo, "/v1/friends/requests", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(other.toString()), resp.body<FriendRequestsResponse>().requests)
        }
    }

    @Test
    fun getFriends_returns401_whenUnauthenticated() = runTest {
        callGet(FakeFriendRepo(), "/v1/friends", bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    // ---------- Test scaffolding ----------

    private class FakeFriendRepo(
        private val sendResult: SendRequestResult = SendRequestResult.Sent,
        private val respondResult: RespondResult = RespondResult.Ok,
        private val friends: List<UserId> = emptyList(),
        private val incoming: List<UserId> = emptyList(),
    ) : FriendRepository {
        var sendCalls = 0
        var lastSend: Pair<UserId, UUID>? = null
        var lastRespondAction: String? = null
        var lastBlock: Pair<UUID, UUID>? = null

        override suspend fun sendRequest(from: UserId, to: UserId): SendRequestResult {
            sendCalls++
            lastSend = from to to.value
            return sendResult
        }

        override suspend fun accept(me: UserId, other: UserId): RespondResult {
            lastRespondAction = "accept"
            return respondResult
        }

        override suspend fun decline(me: UserId, other: UserId): RespondResult {
            lastRespondAction = "decline"
            return respondResult
        }

        override suspend fun block(me: UserId, other: UserId) {
            lastBlock = me.value to other.value
        }

        override suspend fun listFriends(userId: UserId): List<UserId> = friends

        override suspend fun listBlockedUserIds(userId: UserId): Set<UserId> = emptySet()

        override suspend fun listIncomingRequests(userId: UserId): List<UserId> = incoming

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class FakeRecentOpponents(
        private val hasPlayed: Boolean = true,
    ) : RecentOpponentsRepository {
        override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId) = Unit
        override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> = emptyList()
        override suspend fun countDistinctOpponents(userId: UserId): Long = 0L
        override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean = hasPlayed
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

    private suspend fun callPost(
        repo: FriendRepository,
        path: String,
        body: String? = null,
        bearer: String?,
        recent: RecentOpponentsRepository = FakeRecentOpponents(hasPlayed = true),
        assert: suspend (HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { friendsRoutes(repo, recent) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.post(path) {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            assert(resp)
        }
    }

    private suspend fun callGet(
        repo: FriendRepository,
        path: String,
        bearer: String?,
        assert: suspend (HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { friendsRoutes(repo, FakeRecentOpponents(hasPlayed = true)) }
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
