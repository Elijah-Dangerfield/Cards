package com.dangerfield.cards.server.plugins

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression guard for the admin web GUI: the browser calls the deployed server
 * cross-origin with `X-Admin-Token`, so the CORS preflight must allow that header
 * — otherwise the request is rejected with 403 before it's ever authenticated.
 */
class CorsTest {

    private fun preflight(requestHeaders: String, block: suspend (HttpStatusCode) -> Unit) = testApplication {
        application {
            installCors()
            routing { get("/v1/admin/config") { call.respondText("ok") } }
        }
        val response = client.options("/v1/admin/config") {
            header(HttpHeaders.Origin, "http://localhost:8080")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, requestHeaders)
        }
        block(response.status)
    }

    @Test
    fun preflight_allowsAdminTokenHeader() = preflight("X-Admin-Token, X-Admin-Actor") { status ->
        assertNotEquals(HttpStatusCode.Forbidden, status, "CORS rejected the admin headers — the GUI would get 403")
    }

    @Test
    fun preflight_rejectsUnknownHeader() = preflight("X-Not-Allowed") { status ->
        assertEquals(HttpStatusCode.Forbidden, status)
    }
}
