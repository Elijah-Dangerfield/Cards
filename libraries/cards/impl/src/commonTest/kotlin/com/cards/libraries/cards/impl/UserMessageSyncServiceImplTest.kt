package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.UserMessageRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the sync round-trip: pending acks go up in the POST body, the
 * response replaces the local cache, network failure leaves both
 * untouched. Single-flight serialization is implicit (Mutex) and not
 * directly asserted.
 */
class UserMessageSyncServiceImplTest : CoroutineTest() {

    @Test
    fun sync_postsAckedIds_andReplacesCache_withResponse() = runUnitTest {
        val repo = FakeMessageRepo().apply {
            queuePendingAcks("a1", "b2")
        }
        var capturedBody: String? = null
        val service = buildService(repo) { request ->
            capturedBody = request.body.toByteReadPacketAsString()
            respondJson(
                """
                {"messages":[
                  {"id":"x","kind":"dialog","title":"Hi","body":"hi","createdAtEpochMs":1}
                ]}
                """.trimIndent(),
            )
        }

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertNotNull(capturedBody)
        assertTrue(
            capturedBody!!.contains("\"a1\"") && capturedBody!!.contains("\"b2\""),
            "POST body must include both pending ack ids; was: $capturedBody",
        )
        assertEquals(listOf("x"), repo.lastReplacedWith.map { it.id })
    }

    @Test
    fun sync_emptyPendingAcks_stillIssuesRequest() = runUnitTest {
        val repo = FakeMessageRepo()
        var hit = 0
        val service = buildService(repo) {
            hit++
            respondJson("""{"messages":[]}""")
        }
        service.sync()
        assertEquals(1, hit, "empty acks must still POST (server might have new messages)")
    }

    @Test
    fun sync_kindAndExpiry_roundTripThroughResponse() = runUnitTest {
        val repo = FakeMessageRepo()
        val service = buildService(repo) {
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
        service.sync()
        val map = repo.lastReplacedWith.associateBy { it.id }
        assertEquals(UserMessageKind.Dialog, map.getValue("dialog-1").kind)
        assertEquals(UserMessageKind.Inbox, map.getValue("inbox-1").kind)
        assertEquals(99_999L, map.getValue("inbox-1").expiresAtEpochMs)
        assertEquals(
            UserMessageKind.Dialog,
            map.getValue("missing-kind").kind,
            "missing kind falls back to dialog",
        )
    }

    @Test
    fun sync_unknownKind_fallsBackToDialog() = runUnitTest {
        // Forward-compat: a future server adds a 'banner' kind. Old
        // clients shouldn't crash; they show it as a dialog.
        val repo = FakeMessageRepo()
        val service = buildService(repo) {
            respondJson(
                """{"messages":[{"id":"x","kind":"banner","title":"T","body":"B","createdAtEpochMs":1}]}""",
            )
        }
        service.sync()
        assertEquals(UserMessageKind.Dialog, repo.lastReplacedWith.single().kind)
    }

    @Test
    fun sync_serverError_returnsFailure_leavesRepoUntouched() = runUnitTest {
        val repo = FakeMessageRepo().apply { queuePendingAcks("a") }
        val service = buildService(repo) {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
        }

        val result = service.sync()

        assertTrue(result.isFailure)
        assertTrue(
            repo.lastReplacedWith.isEmpty(),
            "5xx must not call replaceCache — local stays as-is",
        )
        assertEquals(listOf("a"), repo.pendingAckIds(), "ack queue stays for retry")
    }

    @Test
    fun sync_hitsExactEndpoint_withPostMethod() = runUnitTest {
        val repo = FakeMessageRepo()
        var capturedPath: String? = null
        var capturedMethod: HttpMethod? = null
        val service = buildService(repo) { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method
            respondJson("""{"messages":[]}""")
        }
        service.sync()
        assertEquals("/v1/me/messages/sync", capturedPath)
        assertEquals(HttpMethod.Post, capturedMethod)
    }

    // ---------- scaffolding ----------

    private fun buildService(
        repo: UserMessageRepository,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): UserMessageSyncServiceImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    coerceInputValues = true
                })
            }
        }
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
        }
        return UserMessageSyncServiceImpl(networkClient, repo)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    private fun io.ktor.http.content.OutgoingContent.toByteReadPacketAsString(): String =
        when (this) {
            is io.ktor.http.content.TextContent -> text
            is io.ktor.http.content.ByteArrayContent -> bytes().decodeToString()
            else -> toString()
        }

    private class FakeMessageRepo : UserMessageRepository {
        private val _unreadInbox = MutableStateFlow<List<UserMessage>>(emptyList())
        var lastReplacedWith: List<UserMessage> = emptyList()
            private set
        private var pending: MutableList<String> = mutableListOf()

        fun queuePendingAcks(vararg ids: String) {
            pending.addAll(ids)
        }

        override fun observeInbox(): Flow<List<UserMessage>> = _unreadInbox.asStateFlow()
        override fun observeUnreadInboxCount(): Flow<Int> =
            MutableStateFlow(0).asStateFlow()

        override suspend fun consumeNextDialog(): UserMessage? = null
        override suspend fun markAllInboxShown(): Int = 0

        override suspend fun replaceCache(messages: List<UserMessage>) {
            lastReplacedWith = messages
            pending.clear()
        }

        override suspend fun pendingAckIds(): List<String> = pending.toList()
    }
}
