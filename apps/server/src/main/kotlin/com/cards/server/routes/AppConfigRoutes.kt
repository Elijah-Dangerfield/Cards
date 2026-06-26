package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.http.clientContext
import com.dangerfield.cards.server.plugins.userId
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/app-config` — returns the resolved override tree keyed by ConfiguredValue.path.
 *
 * The source resolves the tree against the calling client: [clientContext] (platform,
 * app version, country, locale, install id) always, plus the resolved [userId] when the
 * request carries a Supabase JWT. That's what powers per-flag targeting + staged rollouts
 * server-side — the client just merges whatever tree it gets over its defaults.
 *
 * Empty object is a legitimate response — it means "use client defaults". The client
 * always has safe defaults declared in its ConfiguredValue classes, so an empty server config
 * still produces a fully functional app.
 */
fun Route.appConfigRoutes(source: AppConfigSource) {
    get("/v1/app-config") {
        call.respond(source.read(call.clientContext(), call.userId()))
    }
}
