package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the repository's local-first ack flow: the optimistic removal
 * fires immediately so the dialog dismisses without waiting for the
 * network, and a failed server ack does NOT roll back the local
 * removal (the server's `acked_at IS NULL` filter means the next sync
 * just gets the same list back, harmless).
 */
class UserMessageRepositoryImplTest : CoroutineTest() {

    @Test
    fun ack_removesFromUnread_immediately_andCallsServer() = runUnitTest {
        var capturedPath: String? = null
        val repo = buildRepo { request ->
            capturedPath = request.url.encodedPath
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
        }
        repo.setUnread(listOf(message("a"), message("b")))
        repo.ack("a")
        assertEquals(listOf("b"), repo.unread.value.map { it.id })
        assertEquals("/v1/me/messages/a/ack", capturedPath)
    }

    @Test
    fun ack_handlesServerError_withoutRestoringLocal() = runUnitTest {
        val repo = buildRepo {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
        }
        repo.setUnread(listOf(message("a"), message("b")))
        repo.ack("a")
        // The local optimistic removal stands. The server's
        // acked_at IS NULL filter means the next sync hides "a" anyway
        // if the server WAS able to ack — and if it wasn't, the server
        // will return it again on next sync.
        assertEquals(listOf("b"), repo.unread.value.map { it.id })
    }

    @Test
    fun setUnread_overwritesAnyExistingValue() = runUnitTest {
        val repo = buildRepo {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
        }
        repo.setUnread(listOf(message("a"), message("b")))
        repo.setUnread(emptyList())
        assertTrue(repo.unread.value.isEmpty())
    }

    @Test
    fun ack_unknownId_isANoOp() = runUnitTest {
        val repo = buildRepo {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
        }
        repo.setUnread(listOf(message("a")))
        repo.ack("does-not-exist")
        assertEquals(listOf("a"), repo.unread.value.map { it.id })
    }

    private fun buildRepo(
        handler: io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): UserMessageRepositoryImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
        }
        return UserMessageRepositoryImpl(networkClient)
    }

    private fun message(id: String) = UserMessage(
        id = id,
        emoji = null,
        title = "T",
        body = "b",
        deepLink = null,
        createdAtEpochMs = 0L,
    )
}
