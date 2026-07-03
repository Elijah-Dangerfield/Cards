package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Pins the shared error-envelope decode against real Ktor responses: an
 * enveloped 4xx yields code + message; anything else (proxy HTML, empty body,
 * different JSON shape) yields null rather than throwing from a catch block.
 */
class ApiProblemTest : CoroutineTest() {

    @Test
    fun envelopedBody_decodesCodeAndMessage() = runUnitTest {
        val e = failWith("""{"error":{"code":"room_full","message":"Table is full"}}""")

        assertEquals("room_full", e.apiErrorCode())
        assertEquals("Table is full", e.apiErrorMessage())
    }

    @Test
    fun nonEnvelopedJson_yieldsNull() = runUnitTest {
        val e = failWith("""{"detail":"something else"}""")

        assertNull(e.apiProblemOrNull())
    }

    @Test
    fun htmlBody_yieldsNull() = runUnitTest {
        val e = failWith("<html>502 Bad Gateway</html>", contentType = "text/html")

        assertNull(e.apiProblemOrNull())
    }

    @Test
    fun emptyBody_yieldsNull() = runUnitTest {
        val e = failWith("")

        assertNull(e.apiProblemOrNull())
    }

    private suspend fun failWith(
        body: String,
        contentType: String = "application/json",
    ): ResponseException {
        val client = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.BadRequest,
                headers = headersOf("Content-Type", contentType),
            )
        }) {
            install(ContentNegotiation) { json() }
            expectSuccess = true
        }
        return try {
            client.get("/x")
            fail("expected a ResponseException")
        } catch (e: ResponseException) {
            e
        }
    }
}
