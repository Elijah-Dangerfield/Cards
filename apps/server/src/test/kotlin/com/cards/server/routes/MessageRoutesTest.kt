package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.MessageSweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `POST /v1/me/messages/sync`. The repo is faked
 * so the focus stays on the HTTP/JSON shape and JWT gating.
 */
@OptIn(ExperimentalTime::class)
class MessageRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val fixedNow = Instant.parse("2026-03-01T12:00:00Z")

    @Test
    fun sync_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeRepo()
        callSync(repo, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(repo.ackCalls.isEmpty())
        }
    }

    @Test
    fun sync_returnsEmptyArray_whenNothingUnread() = runTest {
        val repo = FakeRepo()
        callSync(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MessageSyncResponse>()
            assertTrue(body.messages.isEmpty())
        }
    }

    @Test
    fun sync_emptyAckList_acksNothing() = runTest {
        val repo = FakeRepo()
        callSync(repo, body = """{"ackedIds":[]}""", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            // Route calls ackMany unconditionally with whatever ids the
            // client sent; the repo treats an empty list as a no-op.
            assertTrue(
                repo.ackCalls.flatMap { it.ids }.isEmpty(),
                "no ids should be flipped when the client sends none",
            )
        }
    }

    @Test
    fun sync_omittedAckedIds_isAccepted() = runTest {
        // Old clients posting `{}` shouldn't 400 — the field defaults to empty.
        val repo = FakeRepo()
        callSync(repo, body = "{}", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun sync_returnsBothKinds_inOrder() = runTest {
        val repo = FakeRepo().apply {
            seed(
                msg(kind = UserMessageKind.Dialog, emoji = "🎉", title = "Hi", body = "first"),
                msg(kind = UserMessageKind.Inbox, emoji = null, title = "Maintenance", body = "second"),
            )
        }
        callSync(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MessageSyncResponse>()
            assertEquals(2, body.messages.size)
            assertEquals("dialog", body.messages[0].kind)
            assertEquals("inbox", body.messages[1].kind)
            assertEquals("🎉", body.messages[0].emoji)
        }
    }

    @Test
    fun sync_passesAckIdsThrough_toRepo() = runTest {
        val repo = FakeRepo()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        callSync(
            repo,
            body = """{"ackedIds":["$a","$b"]}""",
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, repo.ackCalls.size)
            assertEquals(2, repo.ackCalls.single().ids.size)
            assertEquals(userId, repo.ackCalls.single().userId)
        }
    }

    @Test
    fun sync_silentlyDropsMalformedIds_continuesProcessing() = runTest {
        // A future client with a different id format mustn't block the
        // whole batch. Malformed values get dropped, the rest applied.
        val repo = FakeRepo()
        val valid = UUID.randomUUID().toString()
        callSync(
            repo,
            body = """{"ackedIds":["not-a-uuid","$valid","also-bad"]}""",
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, repo.ackCalls.single().ids.size)
            assertEquals(UUID.fromString(valid), repo.ackCalls.single().ids.single())
        }
    }

    @Test
    fun sync_returns400_onMalformedBody() = runTest {
        val repo = FakeRepo()
        callSync(repo, body = "{not json", bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun sync_includesExpiresAt_whenSet() = runTest {
        val repo = FakeRepo().apply {
            seed(msg(expiresAt = fixedNow.plus(kotlin.time.Duration.parse("1h"))))
        }
        callSync(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MessageSyncResponse>()
            assertEquals(
                fixedNow.plus(kotlin.time.Duration.parse("1h")).toEpochMilliseconds(),
                body.messages.single().expiresAtEpochMs,
            )
        }
    }

    @Test
    fun sync_omitsExpiresAt_whenNull() = runTest {
        val repo = FakeRepo().apply { seed(msg(expiresAt = null)) }
        callSync(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MessageSyncResponse>()
            assertNull(body.messages.single().expiresAtEpochMs)
        }
    }

    // ---------- scaffolding ----------

    private fun msg(
        kind: UserMessageKind = UserMessageKind.Dialog,
        emoji: String? = null,
        title: String = "Title",
        body: String = "Body",
        expiresAt: Instant? = null,
    ): UserMessage = UserMessage(
        id = UUID.randomUUID(),
        userId = userId,
        idempotencyKey = UUID.randomUUID().toString(),
        kind = kind,
        emoji = emoji,
        title = title,
        body = body,
        deepLink = null,
        createdAt = fixedNow,
        expiresAt = expiresAt,
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

    private suspend fun callSync(
        repo: UserMessageRepository,
        body: String = """{"ackedIds":[]}""",
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
            val resp = client.post("/v1/me/messages/sync") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assert(resp)
        }
    }

    private data class AckCall(val userId: UserId, val ids: List<UUID>)

    private class FakeRepo : UserMessageRepository {
        val seeded: MutableList<UserMessage> = mutableListOf()
        val ackCalls: MutableList<AckCall> = mutableListOf()

        fun seed(vararg messages: UserMessage) {
            seeded += messages
        }

        override suspend fun create(
            id: UUID,
            userId: UserId,
            idempotencyKey: String,
            kind: UserMessageKind,
            emoji: String?,
            title: String,
            body: String,
            deepLink: String?,
            expiresAt: Instant?,
        ): CreateMessageOutcome = error("not used in sync route tests")

        override suspend fun unreadFor(
            userId: UserId,
            now: Instant,
            limit: Int,
        ): List<UserMessage> =
            seeded.filter { it.userId == userId && it.ackedAt == null }.take(limit)

        override suspend fun ackMany(userId: UserId, ids: List<UUID>, at: Instant): Int {
            ackCalls += AckCall(userId, ids)
            return ids.size
        }

        override suspend fun sweepExpiredAndAcked(now: Instant): MessageSweepResult =
            MessageSweepResult(0, 0)

        override suspend fun deleteAllForUser(userId: UserId) {
            seeded.removeAll { it.userId == userId }
        }
    }
}
