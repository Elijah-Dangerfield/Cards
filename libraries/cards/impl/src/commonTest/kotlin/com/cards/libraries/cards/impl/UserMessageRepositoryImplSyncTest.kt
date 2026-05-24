package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.storage.db.UserMessageEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [UserMessageRepositoryImpl.sync]: pending acks go up in the POST
 * body, the response replaces the local cache, network failure leaves
 * both untouched. Single-flight serialization is implicit (Mutex).
 */
class UserMessageRepositoryImplSyncTest : CoroutineTest() {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    @Test
    fun sync_postsAckedIds_andReplacesCache_withResponse() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            // Two rows already in pending-ack state.
            put(entity("a1", kind = "dialog", ackedPending = true))
            put(entity("b2", kind = "inbox", ackedPending = true))
        }
        var capturedBody: String? = null
        val repo = buildRepo(dao) { request ->
            capturedBody = request.body.toBodyString()
            respondJson(
                """
                {"messages":[
                  {"id":"x","kind":"dialog","title":"Hi","body":"hi","createdAtEpochMs":1}
                ]}
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertNotNull(capturedBody)
        assertTrue(
            capturedBody!!.contains("\"a1\"") && capturedBody!!.contains("\"b2\""),
            "POST body must include both pending ack ids; was: $capturedBody",
        )
        assertEquals(listOf("x"), dao.getAll().map { it.id })
    }

    @Test
    fun sync_emptyPendingAcks_stillIssuesRequest() = runUnitTest {
        val dao = FakeUserMessageDao()
        var hit = 0
        val repo = buildRepo(dao) {
            hit++
            respondJson("""{"messages":[]}""")
        }
        repo.sync()
        assertEquals(1, hit, "empty acks must still POST (server might have new messages)")
    }

    @Test
    fun sync_kindAndExpiry_roundTripThroughResponse() = runUnitTest {
        val dao = FakeUserMessageDao()
        val repo = buildRepo(dao) {
            respondJson(
                """
                {"messages":[
                  {"id":"dialog-1","kind":"dialog","title":"D","body":"d","createdAtEpochMs":1,"emoji":"🎉"},
                  {"id":"inbox-1","kind":"inbox","title":"I","body":"i","createdAtEpochMs":2,"expiresAtEpochMs":99999},
                  {"id":"missing-kind","title":"M","body":"m","createdAtEpochMs":3}
                ]}
                """.trimIndent(),
            )
        }
        repo.sync()
        val map = dao.getAll().associateBy { it.id }
        assertEquals("dialog", map.getValue("dialog-1").kind)
        assertEquals("inbox", map.getValue("inbox-1").kind)
        assertEquals(99_999L, map.getValue("inbox-1").expiresAtEpochMs)
        assertEquals("dialog", map.getValue("missing-kind").kind, "missing kind falls back to dialog")
    }

    @Test
    fun sync_unknownKind_fallsBackToDialog() = runUnitTest {
        val dao = FakeUserMessageDao()
        val repo = buildRepo(dao) {
            respondJson(
                """{"messages":[{"id":"x","kind":"banner","title":"T","body":"B","createdAtEpochMs":1}]}""",
            )
        }
        repo.sync()
        assertEquals("dialog", dao.getAll().single().kind)
    }

    @Test
    fun transient5xxThenSuccess_succeedsAfterRetry() = runUnitTest {
        // messages.sync runs under RetryPolicy.idempotent() — server has a
        // (user_id, idempotency_key) unique index on user_messages, and
        // ack'd ids are no-ops if re-acked. Safe to replay.
        val dao = FakeUserMessageDao().apply {
            put(entity("a", kind = "dialog", ackedPending = true))
        }
        var hitCount = 0
        val repo = buildRepo(dao) {
            hitCount++
            if (hitCount == 1) {
                respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
            } else {
                respondJson("""{"messages":[]}""")
            }
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(2, hitCount, "1 transient 5xx + 1 successful retry")
        assertTrue(dao.getAll().isEmpty(), "ack'd message purged after successful sync")
    }

    @Test
    fun sync_serverError_returnsFailure_leavesRepoUntouched() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("a", kind = "dialog", ackedPending = true))
        }
        val repo = buildRepo(dao) {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        assertEquals(
            listOf("a"),
            dao.getAll().map { it.id },
            "5xx must not call replaceCache — local row stays",
        )
        assertEquals(listOf("a"), dao.pendingAckIds(), "ack queue stays for retry")
    }

    @Test
    fun sync_hitsExactEndpoint_withPostMethod() = runUnitTest {
        val dao = FakeUserMessageDao()
        var capturedPath: String? = null
        var capturedMethod: HttpMethod? = null
        val repo = buildRepo(dao) { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method
            respondJson("""{"messages":[]}""")
        }
        repo.sync()
        assertEquals("/v1/me/messages/sync", capturedPath)
        assertEquals(HttpMethod.Post, capturedMethod)
    }

    // ---------- scaffolding ----------

    private fun buildRepo(
        dao: FakeUserMessageDao,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): UserMessageRepositoryImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    coerceInputValues = true
                })
            }
            // Match production: 4xx/5xx throws so the retry predicate can see it.
            expectSuccess = true
        }
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
        }
        return UserMessageRepositoryImpl(dao, networkClient, fixedClock)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    private fun io.ktor.http.content.OutgoingContent.toBodyString(): String =
        when (this) {
            is io.ktor.http.content.TextContent -> text
            is io.ktor.http.content.ByteArrayContent -> bytes().decodeToString()
            else -> toString()
        }

    private fun entity(
        id: String,
        kind: String,
        ackedPending: Boolean = false,
    ) = UserMessageEntity(
        id = id,
        kind = kind,
        emoji = null,
        title = "Title $id",
        body = "Body $id",
        deepLink = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
        shownAtEpochMs = null,
        ackedPending = ackedPending,
    )

    @Suppress("UNUSED_PARAMETER")
    private fun unusedToKeepImportsLive(kind: UserMessageKind) = Unit
}
