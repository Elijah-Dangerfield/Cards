package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AvatarPacks
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * `GET /v1/avatars` — emoji packs the avatar picker can render. Always
 * returns the starter pack; premium packs appear only when the caller
 * owns the matching product (joined from the inventory table).
 *
 * Cache header is shorter than the old static response because the
 * payload is now per-user: a `private` cache directive ensures CDNs
 * don't bleed one user's pack list to another, and a 1-minute TTL
 * keeps the picker snappy while letting a fresh purchase show up
 * almost immediately.
 *
 * `PATCH /v1/me`'s emoji validation uses the same registry — see
 * `MeRoutes`.
 */
fun Route.avatarRoutes(inventory: InventoryRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/avatars") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val owned = inventory.listOwned(userId)
                .map { it.productId }
                .toSet()
            val packs = AvatarPacks.availableFor(owned).map { it.toDto() }
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 60, visibility = CacheControl.Visibility.Private))
            call.respond(HttpStatusCode.OK, AvatarPackResponse(packs = packs))
        }
    }
}

@Serializable
data class AvatarPackResponse(
    val packs: List<AvatarPackDto>,
)

@Serializable
data class AvatarPackDto(
    val id: String,
    val name: String,
    val emojis: List<String>,
)

private fun AvatarPacks.Pack.toDto(): AvatarPackDto = AvatarPackDto(
    id = id,
    name = name,
    emojis = emojis,
)
