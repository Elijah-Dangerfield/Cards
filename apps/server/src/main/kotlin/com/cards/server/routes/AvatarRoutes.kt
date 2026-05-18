package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AvatarStarterPack
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * `GET /v1/avatars` — returns the curated starter-emoji pack the avatar
 * picker renders.
 *
 * The set is static for V1, so we hand back a 1-day-cacheable response.
 * If/when we add seasonal packs or A/B variations, this can become
 * shorter or per-user — same shape on the wire.
 *
 * Behind the JWT plugin so we can rate-limit per user later, but the
 * payload itself has no per-user content.
 */
fun Route.avatarRoutes() {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/avatars") {
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400))
            call.respond(HttpStatusCode.OK, AvatarPackResponse(starter = AvatarStarterPack.values))
        }
    }
}

@Serializable
data class AvatarPackResponse(
    /** Emojis that any user can pick as their avatar at no cost. */
    val starter: List<String>,
)
