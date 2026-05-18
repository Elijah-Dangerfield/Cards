package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AvatarStarterPack
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.isAnonymousUser
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch

/**
 * `GET /v1/me`  — returns the currently-authenticated user's profile, creating
 *                 it if this is their first contact (get-or-create).
 * `PATCH /v1/me` — update `displayName` and/or `avatarEmoji`. Both optional.
 *
 * Both require a valid Supabase JWT. The JWT plugin populates `call.userId()`.
 *
 * Validation rules for PATCH:
 *  - `displayName`: 1..MAX_NAME_LEN chars after trim. Server-side uniqueness
 *    enforced by the `profiles_display_name_uq` constraint; we surface
 *    409 on conflict.
 *  - `avatarEmoji`: must be a member of [AvatarStarterPack]. Lets us
 *    treat the starter pack as a closed enum without trusting the client.
 */
fun Route.meRoutes(repository: ProfileRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val profile = repository.findOrCreate(userId)
            call.respond(HttpStatusCode.OK, profile.toMeDto(isAnonymous = call.isAnonymousUser()))
        }

        patch("/v1/me") {
            val userId = call.userId() ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val isAnonymous = call.isAnonymousUser()
            val body = call.receive<PatchMeRequest>()

            val cleanedName = body.displayName?.trim()
            if (cleanedName != null && cleanedName.length !in NAME_LENGTH) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    problem("invalid_display_name", "Display name must be ${NAME_LENGTH.first}-${NAME_LENGTH.last} characters."),
                )
            }
            if (body.avatarEmoji != null && !AvatarStarterPack.contains(body.avatarEmoji)) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    problem("invalid_avatar_emoji", "Avatar emoji is not in the starter pack."),
                )
            }

            when (val outcome = repository.update(userId, cleanedName, body.avatarEmoji)) {
                is UpdateProfileOutcome.Success -> call.respond(HttpStatusCode.OK, outcome.profile.toMeDto(isAnonymous = isAnonymous))
                is UpdateProfileOutcome.DisplayNameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    problem("display_name_taken", "That display name is already in use."),
                )
                is UpdateProfileOutcome.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    problem("profile_not_found", "No profile for this user. Hit GET /v1/me first."),
                )
            }
        }
    }
}

private val NAME_LENGTH = 1..32

private fun problem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))
