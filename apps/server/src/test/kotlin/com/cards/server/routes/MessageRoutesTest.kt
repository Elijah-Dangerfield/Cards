package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `/v1/me/messages` (list unread) and
 * `/v1/me/messages/{id}/ack`. The repo is faked so the focus stays on
 * the HTTP/JSON shape and the JWT gating.
 */
@OptIn(ExperimentalTime::class)
class MessageRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val fixedNow = Instant.parse("2026-03-01T12:00:00Z")

    @Test
    fun getMessages_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeRepo()
        callGetMessages(repo, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun getMessages_returnsEmptyArray_whenNothingUnread() = runTest {
        val repo = FakeRepo()
        callGetMessages(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MessagesResponse>()
            assertTrue(body.messages.isEmpty())
        }
    }

    @Test
    fun getMessages_returnsAllUnreadMessages_inOrder() = runTest {
        val repo = FakeRepo().apply {
            seed(
                userMessage(emoji = "🎉", title = "Hi", body = "first", deepLink = null),
                userMessage(emoji = null, title = "Maintenance", body = "second", deepLink = "cards://help"),
            )
        }
        callGetMessages(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MessagesResponse>()
            assertEquals(2, body.messages.size)
            assertEquals("Hi", body.messages[0].title)
            assertEquals("🎉", body.messages[0].emoji)
            assertNull(body.messages[0].deepLink)
            assertEquals("cards://help", body.messages[1].deepLink)
            assertEquals(fixedNow.toEpochMilliseconds(), body.messages[0].createdAtEpochMs)
        }
    }

    @Test
    fun ack_flipsRow_andReturnsNoContent() = runTest {
        val repo = FakeRepo().apply {
            seed(userMessage(emoji = null, title = "T", body = "b", deepLink = null))
        }
        val id = repo.seeded.single().id
        callAck(repo, id = id.toString(), bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertEquals(1, repo.ackCalls.size)
            assertEquals(id, repo.ackCalls.single().id)
            assertEquals(userId, repo.ackCalls.single().userId)
        }
    }

    @Test
    fun ack_returnsNoContent_evenIfRepoSaysNoOp() = runTest {
        // Server intentionally does NOT distinguish "already acked" from
        // "doesn't exist" or "wrong user" — same 204 either way.
        val repo = FakeRepo(ackReturns = false)
        callAck(repo, id = UUID.randomUUID().toString(), bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
        }
    }

    @Test
    fun ack_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeRepo()
        callAck(repo, id = UUID.randomUUID().toString(), bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(repo.ackCalls.isEmpty())
        }
    }

    @Test
    fun ack_returns400_forNonUuidId() = runTest {
        val repo = FakeRepo()
        callAck(repo, id = "not-a-uuid", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(repo.ackCalls.isEmpty())
        }
    }

    // ---------- scaffolding ----------

    private fun userMessage(
        emoji: String?,
        title: String,
        body: String,
        deepLink: String?,
    ): UserMessage = UserMessage(
        id = UUID.randomUUID(),
        userId = userId,
        idempotencyKey = UUID.randomUUID().toString(),
        emoji = emoji,
        title = title,
        body = body,
        deepLink = deepLink,
        createdAt = fixedNow,
        ackedAt = null,
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

    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    private suspend fun callGetMessages(
        repo: UserMessageRepository,
        bearer: String?,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { messageRoutes(repo, fixedClock) }
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    })
                }
            }
            val resp = client.get("/v1/me/messages") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(resp)
        }
    }

    private suspend fun callAck(
        repo: UserMessageRepository,
        id: String,
        bearer: String?,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { messageRoutes(repo, fixedClock) }
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    })
                }
            }
            val resp = client.post("/v1/me/messages/$id/ack") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(resp)
        }
    }

    private data class AckCall(val userId: UserId, val id: UUID)

    private class FakeRepo(private val ackReturns: Boolean = true) : UserMessageRepository {
        val seeded: MutableList<UserMessage> = mutableListOf()
        val ackCalls: MutableList<AckCall> = mutableListOf()

        fun seed(vararg messages: UserMessage) {
            seeded += messages
        }

        override suspend fun create(
            id: UUID,
            userId: UserId,
            idempotencyKey: String,
            emoji: String?,
            title: String,
            body: String,
            deepLink: String?,
        ): CreateMessageOutcome = error("not used in route tests")

        override suspend fun unreadFor(userId: UserId, limit: Int): List<UserMessage> =
            seeded.filter { it.userId == userId && it.ackedAt == null }.take(limit)

        override suspend fun ack(userId: UserId, id: UUID, at: Instant): Boolean {
            ackCalls += AckCall(userId, id)
            return ackReturns
        }

        override suspend fun deleteAllForUser(userId: UserId) {
            seeded.removeAll { it.userId == userId }
        }
    }
}
