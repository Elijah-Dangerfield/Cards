package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * Public profile-resolution endpoint. A thin HTTP/JSON shell over
 * [ProfileRepository.findById].
 *
 * - `GET /v1/profiles?ids=a,b,c` — resolve a batch of user ids to their public
 *   display fields (name / emoji / avatar background). Unknown ids are silently
 *   omitted; the response carries only the rows that resolved.
 *
 * The social surfaces — recently-played-with, the friends list, the friend-
 * requests inbox — all return *bare* user ids ([RecentOpponentsResponse],
 * [FriendsResponse], [FriendRequestsResponse]). This is the one endpoint that
 * turns those ids into something renderable, so the client never ships its own
 * directory of strangers. Auth-gated (you must be a signed-in user to resolve
 * anyone), but not relationship-gated: the only ids a client can legitimately
 * obtain are ones the server already handed it from a social list.
 */
fun Route.profilesRoutes(profiles: ProfileRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/profiles") {
            call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val raw = call.request.queryParameters["ids"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()

            val parsed = raw.map { it.toUserIdOrNull() }
            if (parsed.any { it == null }) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    profilesProblem("invalid_user_id", "Every id must be a valid UUID."),
                )
            }

            val ids = parsed.filterNotNull().take(MAX_IDS)
            // One batched read instead of a round-trip per id. Re-associate by id
            // so the response preserves request order and silently omits unknowns.
            val byId = profiles.findByIds(ids).associateBy { it.userId }
            val resolved = ids.mapNotNull { id -> byId[id]?.toPublicDto() }

            call.respond(HttpStatusCode.OK, ProfilesResponse(profiles = resolved))
        }
    }
}

private const val MAX_IDS = 100

private fun String.toUserIdOrNull(): UserId? = try {
    takeIf { it.isNotBlank() }?.let { UserId.parse(it) }
} catch (_: IllegalArgumentException) {
    null
}

private fun profilesProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

private fun com.dangerfield.cards.server.domain.Profile.toPublicDto() = PublicProfileDto(
    id = userId.value.toString(),
    displayName = displayName,
    avatarEmoji = avatarEmoji,
    avatarBackgroundColor = avatarBackgroundColor,
)

@Serializable
data class ProfilesResponse(
    val schemaVersion: Int = 1,
    /** The resolved public profiles; unknown ids are omitted. */
    val profiles: List<PublicProfileDto>,
)

/**
 * The public-facing slice of a profile — what one user is allowed to see about
 * another. Deliberately excludes email, install id, timestamps, and anything
 * else private to the account.
 */
@Serializable
data class PublicProfileDto(
    val id: String,
    val displayName: String,
    val avatarEmoji: String,
    /** Hex string, or null = "use the theme default". */
    val avatarBackgroundColor: String? = null,
)
