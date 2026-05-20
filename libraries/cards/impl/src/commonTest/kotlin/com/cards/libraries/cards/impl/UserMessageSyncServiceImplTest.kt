package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the user-message sync flow: GET /v1/me/messages → parse →
 * hand off to [UserMessageRepository.setUnread]. Same MockEngine
 * pattern as [ChipsSyncServiceImplTest] for consistency.
 *
 * What we pin:
 *  - 200 with 0/1/many messages — all map cleanly to the repo
 *  - Optional fields (emoji, deepLink) survive round-trip both ways
 *  - 5xx / network error → repo cache stays untouched (no clobber on
 *    a transient blip)
 *  - The endpoint hit is exactly the documented one — typo guard
 */
class UserMessageSyncServiceImplTest : CoroutineTest() {

    @Test
    fun sync_emptyUnread_setsEmptyOnRepo() = runUnitTest {
        val repo = FakeMessageRepo().apply {
            // Seed with a stale value so we can confirm the sync overwrites it.
            seed(listOf(message("stale", "old", "old body")))
        }
        val service = buildService(repo) {
            respondJson("""{"schemaVersion":1,"messages":[]}""")
        }

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertTrue(repo.unread.value.isEmpty(), "empty server response must overwrite the cache")
    }

    @Test
    fun sync_singleMessage_roundTripsAllFields() = runUnitTest {
        val repo = FakeMessageRepo()
        val service = buildService(repo) {
            respondJson(
                """
                {"schemaVersion":1,"messages":[
                  {"id":"abc","emoji":"🎁","title":"Merry","body":"Have 5000 chips.","deepLink":"cards://shop","createdAtEpochMs":1700000000000}
                ]}
                """.trimIndent(),
            )
        }

        service.sync()

        val single = repo.unread.value.single()
        assertEquals("abc", single.id)
        assertEquals("🎁", single.emoji)
        assertEquals("Merry", single.title)
        assertEquals("Have 5000 chips.", single.body)
        assertEquals("cards://shop", single.deepLink)
        assertEquals(1_700_000_000_000L, single.createdAtEpochMs)
    }

    @Test
    fun sync_omittedOptionalFields_landAsNull() = runUnitTest {
        // explicitNulls=false on the server means emoji/deepLink can
        // be absent entirely. The DTO must default them to null — this
        // is the typing assertion that catches a "required" slip.
        val repo = FakeMessageRepo()
        val service = buildService(repo) {
            respondJson(
                """
                {"messages":[
                  {"id":"abc","title":"Heads up","body":"Maintenance Sunday.","createdAtEpochMs":1700000000000}
                ]}
                """.trimIndent(),
            )
        }

        service.sync()

        val single = repo.unread.value.single()
        assertNull(single.emoji)
        assertNull(single.deepLink)
    }

    @Test
    fun sync_multipleMessages_preservesOrder() = runUnitTest {
        val repo = FakeMessageRepo()
        val service = buildService(repo) {
            respondJson(
                """
                {"messages":[
                  {"id":"1","title":"A","body":"a","createdAtEpochMs":1},
                  {"id":"2","title":"B","body":"b","createdAtEpochMs":2},
                  {"id":"3","title":"C","body":"c","createdAtEpochMs":3}
                ]}
                """.trimIndent(),
            )
        }

        service.sync()

        assertEquals(listOf("1", "2", "3"), repo.unread.value.map { it.id })
    }

    @Test
    fun sync_serverError_returnsFailure_andLeavesCacheUntouched() = runUnitTest {
        val seeded = listOf(message("keep", "Keep me", "still relevant"))
        val repo = FakeMessageRepo().apply { seed(seeded) }
        val service = buildService(repo) {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
        }

        val result = service.sync()

        assertTrue(result.isFailure)
        assertEquals(
            seeded,
            repo.unread.value,
            "5xx must not clobber the local cache — next foreground will retry",
        )
    }

    @Test
    fun sync_hitsExactEndpoint() = runUnitTest {
        val repo = FakeMessageRepo()
        var capturedPath: String? = null
        val service = buildService(repo) { request ->
            capturedPath = request.url.encodedPath
            respondJson("""{"messages":[]}""")
        }

        service.sync()

        assertEquals("/v1/me/messages", capturedPath, "path drift would break sync silently")
    }

    // ---------- Scaffolding ----------

    private fun buildService(
        repo: UserMessageRepository,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): UserMessageSyncServiceImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        coerceInputValues = true
                    },
                )
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

    private fun message(id: String, title: String, body: String) = UserMessage(
        id = id,
        emoji = null,
        title = title,
        body = body,
        deepLink = null,
        createdAtEpochMs = 0L,
    )

    private class FakeMessageRepo : UserMessageRepository {
        private val _unread = MutableStateFlow<List<UserMessage>>(emptyList())
        override val unread: StateFlow<List<UserMessage>> = _unread.asStateFlow()
        var ackCalls: MutableList<String> = mutableListOf()
            private set

        fun seed(messages: List<UserMessage>) {
            _unread.value = messages
        }

        override suspend fun setUnread(messages: List<UserMessage>) {
            _unread.value = messages
        }

        override suspend fun ack(id: String) {
            ackCalls += id
            _unread.value = _unread.value.filterNot { it.id == id }
        }
    }
}
