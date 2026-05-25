package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AvatarPacks
import com.dangerfield.cards.server.domain.AvatarPalette
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * `GET /v1/avatars` — emoji packs the avatar picker can render.
 *
 * **Unauthenticated** on purpose. The response is identical for every
 * caller — it's the full registry of every pack (starter + premium)
 * with their `unlockProductId`s, and the client filters against its
 * own inventory. Anon lets the fetch fly before the Supabase JWT
 * lands during onboarding, removing the cold-start race that
 * otherwise forces the picker onto its hardcoded fallback list.
 *
 * Returns the **full** registry — starter pack plus every premium pack
 * — each row carrying its `unlockProductId`. The client filters
 * against local inventory rather than waiting on the server's inventory
 * join, so a freshly-purchased pack appears in the picker the moment
 * the optimistic local row is written (no need to wait for sync).
 *
 * `PATCH /v1/me`'s emoji validation uses the same registry and still
 * gates on inventory server-side — see `MeRoutes`. Anon access here
 * doesn't widen the write surface: an anon caller can read the
 * registry, but can't patch a profile without a JWT.
 */
fun Route.avatarRoutes() {
    get("/v1/avatars") {
        val packs = AvatarPacks.all.map { it.toDto() }
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 60, visibility = CacheControl.Visibility.Public))
        call.respond(
            HttpStatusCode.OK,
            AvatarPackResponse(
                packs = packs,
                backgroundPalette = AvatarPalette.values,
            ),
        )
    }
}

@Serializable
data class AvatarPackResponse(
    val packs: List<AvatarPackDto>,
    /**
     * Allowed background color swatches for the avatar circle. Server-
     * authoritative so adding a swatch later is server-only. Empty list
     * is a legitimate response (= "no per-user color customization
     * available").
     */
    val backgroundPalette: List<String> = emptyList(),
)

@Serializable
data class AvatarPackDto(
    val id: String,
    val name: String,
    val emojis: List<String>,
    /**
     * Product id that unlocks this pack. `null` for the starter pack
     * (always available). The client filters the picker against local
     * inventory using this field — see `EditProfileViewModel`.
     */
    val unlockProductId: String? = null,
)

private fun AvatarPacks.Pack.toDto(): AvatarPackDto = AvatarPackDto(
    id = id,
    name = name,
    emojis = emojis,
    unlockProductId = unlockProductId,
)

