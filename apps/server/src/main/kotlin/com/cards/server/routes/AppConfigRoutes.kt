package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AppConfigSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/app-config` — returns the sparse override tree keyed by ConfiguredValue.path.
 *
 * Empty object is a legitimate response — it means "use client defaults". The client
 * always has safe defaults declared in FeatureConfig classes, so an empty server config
 * still produces a fully functional app.
 */
fun Route.appConfigRoutes(source: AppConfigSource) {
    get("/v1/app-config") {
        call.respond(source.read())
    }
}
