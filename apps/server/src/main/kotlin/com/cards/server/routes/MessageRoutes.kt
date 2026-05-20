package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import java.util.UUID

/**
 * User-facing surface for in-app messages, modeled on the chips +
 * inventory sync endpoints.
 *
 * `POST /v1/me/messages/sync` is the single round-trip:
 *  - Body: `{ackedIds: [uuid, ...]}` — the client tells the server
 *    what it acked locally since the previous sync.
 *  - Response: every unread + unexpired message for the caller (both
 *    dialog and inbox kinds). The client diff-and-deletes anything in
 *    its local DB that didn't come back.
 *
 * Why a single sync endpoint instead of GET + per-id POST: one round-
 * trip, atomic ack-then-fetch, and the response IS the truth — the
 * client doesn't need to know whether each ack landed because the
 * next response tells it ("this id isn't here anymore → drop it
 * locally").
 *
 * Acks are idempotent: an id that was already acked (or never existed,
 * or belongs to another user) is silently skipped.
 */
@OptIn(ExperimentalTime::class)
fun Route.messageRoutes(
    repository: UserMessageRepository,
    clock: Clock,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        post("/v1/me/messages/sync") {
            val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val body = try {
                call.receive<MessageSyncRequest>()
            } catch (_: BadRequestException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to mapOf(
                        "code" to "invalid_body",
                        "message" to "Malformed JSON body.",
                    )),
                )
            }
            val now = clock.now()

            val parsedIds = body.ackedIds.mapNotNull { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
            // Silently drop malformed UUIDs — the client may include
            // legacy ids it can't migrate; rejecting the whole batch
            // would block its progress.

            repository.ackMany(userId = userId, ids = parsedIds, at = now)
            val unread = repository.unreadFor(userId = userId, now = now)

            call.respond(
                HttpStatusCode.OK,
                MessageSyncResponse(messages = unread.map { it.toDto() }),
            )
        }
    }
}

@Serializable
data class MessageSyncRequest(
    val ackedIds: List<String> = emptyList(),
)

@Serializable
data class MessageSyncResponse(
    val schemaVersion: Int = 1,
    val messages: List<UserMessageDto>,
)

@Serializable
data class UserMessageDto(
    val id: String,
    val kind: String,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
)

private fun UserMessage.toDto(): UserMessageDto = UserMessageDto(
    id = id.toString(),
    kind = kind.wire,
    emoji = emoji,
    title = title,
    body = body,
    deepLink = deepLink,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    expiresAtEpochMs = expiresAt?.toEpochMilliseconds(),
)
