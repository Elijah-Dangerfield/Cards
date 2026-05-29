package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.domain.AvatarPacks
import com.dangerfield.cards.server.domain.AvatarPalette
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.DELETE_ACCOUNT_LIMIT
import com.dangerfield.cards.server.plugins.PROFILE_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.isAnonymousUser
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * `GET /v1/me`              — returns the currently-authenticated user's profile,
 *                             creating it if this is their first contact (get-or-create).
 * `GET /v1/me/active-rooms` — the rooms the caller currently holds a seat in. Used by
 *                             the client on cold launch to offer "rejoin or forfeit"
 *                             before silently stranding a player in a room they
 *                             abandoned across an app kill. In-memory snapshot — no
 *                             persistence needed today.
 * `PATCH /v1/me`            — update `displayName` and/or `avatarEmoji`. Both optional.
 * `DELETE /v1/me`           — permanent account deletion: removes the Supabase
 *                             auth.users row (via the Admin API) AND our profile row.
 *                             Returns 204 on success.
 *
 * All three require a valid Supabase JWT. The JWT plugin populates
 * `call.userId()`.
 *
 * Validation rules for PATCH:
 *  - `displayName`: 1..MAX_NAME_LEN chars after trim. Server-side uniqueness
 *    enforced by the `profiles_display_name_uq` constraint; we surface
 *    409 on conflict.
 *  - `avatarEmoji`: must be in one of the caller's available
 *    [AvatarPacks] (starter + owned premium packs). Joining inventory
 *    here keeps "owning the pack permits picking its emojis" enforced
 *    server-side; clients can't sidestep by patching with a random
 *    emoji string.
 *
 * DELETE ordering: admin call first (revokes the user's sessions immediately,
 * so even if the local profile delete fails the user can't come back via
 * the same account), then local profile cleanup. An orphan profile is
 * recoverable by a future sweep; an orphan auth.users with a live JWT is
 * a security problem.
 */
fun Route.meRoutes(
    repository: ProfileRepository,
    adminClient: SupabaseAdminClient,
    inventory: InventoryRepository,
    wallet: WalletRepository,
    messages: UserMessageRepository,
    rooms: RoomService,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val profile = repository.findOrCreate(userId)
            // Tag the profile with the caller's install_id (header). The
            // L1 orphan-cleanup task — once it lands — consults this column
            // to find prior anon rows that share the same install_id.
            // Malformed headers are silently dropped; clients on builds
            // older than V49 send no header and skip the tag entirely.
            call.request.headers[INSTALL_ID_HEADER]
                ?.let { parseUuidOrNull(it) }
                ?.let { repository.touchInstallId(userId, it) }
            call.respond(HttpStatusCode.OK, profile.toMeDto(isAnonymous = call.isAnonymousUser()))
        }

        get("/v1/me/active-rooms") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val active = rooms.snapshot().filter { it.memberFor(userId) != null }
            call.respond(
                HttpStatusCode.OK,
                ActiveRoomsResponse(rooms = active.map { it.toDto() }),
            )
        }

        rateLimit(RateLimitName(PROFILE_WRITE_LIMIT)) {
            patch("/v1/me") {
                val userId = call.userId() ?: return@patch call.respond(HttpStatusCode.Unauthorized)
                val isAnonymous = call.isAnonymousUser()
                val body = call.receive<PatchMeRequest>()

                val cleanedName = body.displayName?.trim()
                if (cleanedName != null && cleanedName.length !in NAME_LENGTH) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        problem(
                            "invalid_display_name",
                            "Display name must be ${NAME_LENGTH.first}-${NAME_LENGTH.last} characters."
                        ),
                    )
                }
                if (body.avatarEmoji != null) {
                    val owned = inventory.listOwned(userId).map { it.productId }.toSet()
                    if (!AvatarPacks.isEmojiAvailable(body.avatarEmoji, owned)) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            problem(
                                "invalid_avatar_emoji",
                                "Avatar emoji is not available on your account."
                            ),
                        )
                    }
                }
                if (body.avatarBackgroundColor != null && !AvatarPalette.isValid(body.avatarBackgroundColor)) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        problem(
                            "invalid_avatar_background_color",
                            "Avatar background color is not in the palette."
                        ),
                    )
                }

                when (val outcome = repository.update(
                    userId = userId,
                    displayName = cleanedName,
                    avatarEmoji = body.avatarEmoji,
                    avatarBackgroundColor = body.avatarBackgroundColor?.lowercase(),
                    clearAvatarBackgroundColor = body.clearAvatarBackgroundColor,
                )) {
                    is UpdateProfileOutcome.Success -> call.respond(
                        HttpStatusCode.OK,
                        outcome.profile.toMeDto(isAnonymous = isAnonymous)
                    )

                    is UpdateProfileOutcome.DisplayNameTaken -> call.respond(
                        HttpStatusCode.Conflict,
                        problem("display_name_taken", "That display name is already in use."),
                    )

                    is UpdateProfileOutcome.NotFound -> call.respond(
                        HttpStatusCode.NotFound,
                        problem(
                            "profile_not_found",
                            "No profile for this user. Hit GET /v1/me first."
                        ),
                    )
                }
            }
        }

        rateLimit(RateLimitName(DELETE_ACCOUNT_LIMIT)) {
            delete("/v1/me") {
                val userId =
                    call.userId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                if (call.isAnonymousUser()) {
                    // Anonymous accounts have no claimed identity to delete —
                    // signing out already drops the only handle the client
                    // has on this user. The client guards against this too,
                    // but server is authoritative (the JWT carries
                    // is_anonymous, can't be spoofed).
                    return@delete call.respond(
                        HttpStatusCode.Forbidden,
                        problem(
                            "anonymous_not_allowed",
                            "Anonymous accounts can't be deleted. Sign out instead, or claim your account with email or OAuth first.",
                        ),
                    )
                }
                when (val admin = adminClient.deleteUser(userId)) {
                    DeleteUserResult.Success, DeleteUserResult.AlreadyGone -> {
                        // Order: admin (revoke auth + sessions) first, then
                        // local data. Each repo's delete is idempotent so a
                        // mid-cascade crash leaves us with a recoverable
                        // partial state, not stuck data.
                        wallet.deleteAllForUser(userId)
                        messages.deleteAllForUser(userId)
                        repository.delete(userId)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    DeleteUserResult.NotConfigured -> call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        problem(
                            "delete_not_configured",
                            "Account deletion is not enabled on this server. Set SUPABASE_SERVICE_ROLE_KEY and redeploy.",
                        ),
                    )

                    is DeleteUserResult.Failure -> {
                        LoggerFactory.getLogger("MeRoutes").error(
                            "Supabase admin delete failed for user {} (status={})",
                            userId,
                            admin.statusCode,
                            admin.cause,
                        )
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            problem(
                                "delete_failed",
                                "Could not delete account right now. Please try again."
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val NAME_LENGTH = 1..32

/**
 * Per-install identifier set by the client on every authenticated request
 * (see `ClientHeaders.HEADER_INSTALL_ID` in :libraries:networking). Used by
 * the L1 orphan-cleanup design — see docs/recovery-and-orphaned-accounts.md.
 */
internal const val INSTALL_ID_HEADER: String = "X-Install-Id"

/**
 * Parse [raw] as a UUID, or null if it doesn't conform. We swallow the
 * malformed case rather than 400-ing because the header is opaque metadata
 * — the request itself is independent of whether we can record it.
 */
private fun parseUuidOrNull(raw: String): UUID? =
    Catching { UUID.fromString(raw) }.getOrNull()

private fun problem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))
