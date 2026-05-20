package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import java.util.UUID

/**
 * User-facing surface for in-app messages.
 *
 * `GET /v1/me/messages` returns the caller's unread messages (acked
 * filtered out at the SQL level via a partial index). Empty array is
 * the steady state — fetching is cheap.
 *
 * `POST /v1/me/messages/{id}/ack` flips the row's `acked_at`. Returns
 * 204 whether or not the row was actually flipped (idempotent; the
 * server doesn't distinguish "already acked" from "doesn't exist /
 * doesn't belong to you" — same response either way so callers can't
 * probe for IDs).
 *
 * Both endpoints require a Supabase JWT. The authoring side
 * (`/v1/admin/messages` + the chip-grant attach path) lives in
 * `AdminRoutes.kt`.
 */
@OptIn(ExperimentalTime::class)
fun Route.messageRoutes(
    repository: UserMessageRepository,
    clock: Clock,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/messages") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val unread = repository.unreadFor(userId)
            call.respond(
                HttpStatusCode.OK,
                MessagesResponse(messages = unread.map { it.toDto() }),
            )
        }

        post("/v1/me/messages/{id}/ack") {
            val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val rawId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val id = try {
                UUID.fromString(rawId)
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest)
            }
            repository.ack(userId = userId, id = id, at = clock.now())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class MessagesResponse(
    val schemaVersion: Int = 1,
    val messages: List<UserMessageDto>,
)

@Serializable
data class UserMessageDto(
    val id: String,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAtEpochMs: Long,
)

private fun UserMessage.toDto(): UserMessageDto = UserMessageDto(
    id = id.toString(),
    emoji = emoji,
    title = title,
    body = body,
    deepLink = deepLink,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
)
