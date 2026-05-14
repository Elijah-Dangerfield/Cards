package com.dangerfield.cards.server.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Liveness/readiness probe. Stays /_health (not /v1/_health) so it's not tied
 * to a versioned API and load balancers can hit it unconditionally.
 */
fun Route.healthRoutes() {
    get("/_health") {
        call.respond(mapOf("ok" to true))
    }
}
