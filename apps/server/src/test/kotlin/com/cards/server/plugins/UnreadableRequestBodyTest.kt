package com.dangerfield.cards.server.plugins

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ENG-73 / CARDS-CB / CARDS-D9. A phone whose upload dies mid-request sends an
 * empty or cut-off body. That is the client's network, not a server fault, so it
 * answers 400 instead of the 500 + Sentry issue it used to raise.
 */
class UnreadableRequestBodyTest {

    @Serializable
    private data class SyncBody(val handsPlayed: Int)

    private fun testRoute(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            installSerialization()
            installStatusPages()
            routing {
                post("/sync") { call.respond(call.receive<SyncBody>()) }
            }
        }
        block(client)
    }

    @Test
    fun anEmptyBodyIsABadRequest() = testRoute { client ->
        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun aTruncatedBodyIsABadRequest() = testRoute { client ->
        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"handsPla""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun aBodyWithNoContentTypeIsABadRequest() = testRoute { client ->
        val response = client.post("/sync") { setBody("""{"handsPlayed":3}""") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
