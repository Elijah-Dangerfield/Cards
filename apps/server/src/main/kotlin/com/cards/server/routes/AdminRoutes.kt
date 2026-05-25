package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.WalletRepository
import kotlin.time.Instant
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * Token-gated cleanup + maintenance endpoints. Not part of the public
 * client surface — designed to be triggered from a scheduler (GitHub
 * Actions cron, Fly cron, external uptime monitor with auth header).
 *
 * Auth: bearer-style `X-Admin-Token: <ADMIN_API_TOKEN>`. We don't use the
 * JWT plugin here because the caller is a machine, not a Supabase user.
 *
 * Cadence recommendation (V1): daily for the anon sweep. Two-line cron-
 * style GitHub Actions workflow at the repo root is the easiest path —
 * see DEPLOY.md.
 */
@OptIn(ExperimentalTime::class)
fun Route.adminRoutes(
    config: AdminConfig,
    sweep: OrphanAnonymousSweep,
    rooms: RoomService,
    wallets: WalletRepository,
    messages: UserMessageRepository,
    clock: Clock,
) {
    route("/v1/admin") {
        post("/sweep-anonymous-users") {
            if (!call.authenticatedAsAdmin(config)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val result = sweep.run(maxInactiveAge = config.orphanAnonTtlDays.days)
            call.respond(
                if (result.notConfigured) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                SweepResponse(
                    candidatesFound = result.candidatesFound,
                    deleted = result.deleted,
                    failedToDelete = result.failedToDelete,
                    notConfigured = result.notConfigured,
                ),
            )
        }

        /**
         * Operational dashboard for live rooms. Returns one entry per
         * room — code, host, seat occupancy, presence counts — so an
         * operator can spot abandoned rooms before the sweep ticks,
         * answer "how busy is MP right now," and verify that the sweep
         * is doing its job between cron runs.
         *
         * No PII beyond what's already public over the lobby socket
         * (display names + presence). Member-level detail is
         * intentionally summary-only — full member lists go up
         * quadratically with concurrent rooms and aren't needed for
         * ops triage.
         */
        get("/rooms") {
            if (!call.authenticatedAsAdmin(config)) {
                return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val snapshot = rooms.snapshot()
            call.respond(
                HttpStatusCode.OK,
                AdminRoomListResponse(
                    rooms = snapshot.map { room ->
                        AdminRoomSummary(
                            code = room.code,
                            hostUserId = room.hostUserId.value.toString(),
                            createdAtEpochMs = room.createdAt.toEpochMilliseconds(),
                            status = room.status.name,
                            maxSeats = room.maxSeats,
                            memberCount = room.members.size,
                            connectedCount = room.members.count { it.isConnected },
                            disconnectedCount = room.members.count { !it.isConnected },
                        )
                    },
                ),
            )
        }

        /**
         * Storage hygiene for `user_messages`. Removes:
         *   - Every acked row (regardless of age) — once the user has
         *     seen + acked a message, the row is dead weight.
         *   - Unacked rows whose `expires_at` has passed — the unread-
         *     fetch filter already hides these, so deletion is cleanup,
         *     not a behavior change.
         *
         * Intended to run on a slow cron (nightly is plenty). Two
         * counts come back so the caller can log them; a spike in
         * `expiredUnackedPurged` is worth a look — it means users
         * weren't seeing their notices in time.
         */
        post("/sweep-expired-messages") {
            if (!call.authenticatedAsAdmin(config)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val result = messages.sweepExpiredAndAcked(clock.now())
            call.respond(
                HttpStatusCode.OK,
                MessageSweepResponse(
                    ackedPurged = result.ackedPurged,
                    expiredUnackedPurged = result.expiredUnackedPurged,
                    total = result.total,
                ),
            )
        }

        /**
         * Credits (or debits) chips on a specific user's wallet. The
         * primary use is "support needs to make a player whole after
         * something went wrong in prod."
         *
         * Body: [GrantChipsRequest]. `userId` must be a UUID. `delta` is
         * signed — negative values debit. `reason` is a free-form short
         * string that gets stored on the ledger row prefixed with
         * `admin_grant:` so the audit trail groups cleanly when querying
         * `wallet_events.reason LIKE 'admin_grant:%'`.
         *
         * Idempotency: if the caller omits [GrantChipsRequest.idempotencyKey],
         * the server generates one. Passing a stable key lets a retry
         * (e.g. the operator's network blipped) be a safe no-op.
         *
         * Outcomes mirror the wallet sync route — Applied (first time or
         * replay) or InsufficientChips (negative delta would dip below
         * zero; ledger row is NOT written, balance unchanged).
         */
        post("/grant-chips") {
            if (!call.authenticatedAsAdmin(config)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val body = try {
                call.receive<GrantChipsRequest>()
            } catch (_: BadRequestException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_body", "Malformed JSON body."),
                )
            }
            val parsedUserId = try {
                UserId(UUID.fromString(body.userId))
            } catch (_: IllegalArgumentException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_user_id", "userId must be a UUID."),
                )
            }
            val trimmedReason = body.reason.trim()
            if (trimmedReason.isEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_reason", "reason must be a non-empty string."),
                )
            }
            if (body.delta == 0L) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_delta", "delta must be non-zero."),
                )
            }
            val attachedMessage = body.message
            if (attachedMessage != null) {
                val validation = validateMessagePayload(attachedMessage)
                if (validation != null) return@post call.respond(validation.first, validation.second)
            }
            val key = body.idempotencyKey?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val outcome = wallets.apply(
                userId = parsedUserId,
                idempotencyKey = key,
                delta = body.delta,
                reason = "admin_grant:$trimmedReason",
            )
            val messageDto: AdminMessageDto? = if (
                attachedMessage != null &&
                outcome is ApplyOutcome.Applied &&
                !outcome.wasAlreadyApplied
            ) {
                // Only attach a message when the grant actually moved chips.
                // Replays don't re-attach (the original call already did);
                // InsufficientChips means the chips never landed so a
                // "we credited you" dialog would be a lie.
                val result = messages.create(
                    id = UUID.randomUUID(),
                    userId = parsedUserId,
                    idempotencyKey = "grant-chips:$key",
                    // Chip grants attach as Dialog only — a fresh chip
                    // arrival is a moment, not a log entry. If the
                    // operator wants an inbox follow-up, they should
                    // send a separate /v1/admin/messages call.
                    kind = UserMessageKind.Dialog,
                    emoji = attachedMessage.emoji,
                    title = attachedMessage.title.trim(),
                    body = attachedMessage.body.trim(),
                    deepLink = attachedMessage.deepLink?.takeUnless { it.isBlank() },
                    expiresAt = attachedMessage.expiresAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
                )
                result.message.toAdminDto()
            } else {
                null
            }
            val (status, response) = when (outcome) {
                is ApplyOutcome.Applied -> HttpStatusCode.OK to GrantChipsResponse(
                    balance = outcome.balance,
                    outcome = if (outcome.wasAlreadyApplied) {
                        GrantChipsOutcomeDto.AlreadyApplied
                    } else {
                        GrantChipsOutcomeDto.Applied
                    },
                    idempotencyKey = key,
                    message = messageDto,
                )
                is ApplyOutcome.InsufficientChips -> HttpStatusCode.Conflict to GrantChipsResponse(
                    balance = outcome.balance,
                    outcome = GrantChipsOutcomeDto.InsufficientChips,
                    idempotencyKey = key,
                    message = null,
                )
            }
            call.respond(status, response)
        }

        /**
         * Schedules an in-app dialog for [SendMessageRequest.userId]. The
         * dialog appears on the user's next foreground / cold-boot, with
         * the emoji bubble, title, body, and CTA the operator filled in.
         *
         * Body validation mirrors the chip-attach path so the two
         * endpoints agree on what a "valid message" looks like. Returns
         * the created (or replayed) message id so the operator can
         * cross-reference the run with the recipient's ack later.
         *
         * Use cases: maintenance heads-up, season launch, "we fixed the
         * bug that ate your hand" support outreach. For broadcasts,
         * call N times — V1 deliberately doesn't have a fan-out endpoint
         * (avoids accidentally messaging the entire user base from a
         * single typo).
         */
        post("/messages") {
            if (!call.authenticatedAsAdmin(config)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val body = try {
                call.receive<SendMessageRequest>()
            } catch (_: BadRequestException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_body", "Malformed JSON body."),
                )
            }
            val parsedUserId = try {
                UserId(UUID.fromString(body.userId))
            } catch (_: IllegalArgumentException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_user_id", "userId must be a UUID."),
                )
            }
            val validation = validateMessagePayload(body.message)
            if (validation != null) return@post call.respond(validation.first, validation.second)
            val kind = try {
                body.kind?.let { UserMessageKind.parse(it) } ?: UserMessageKind.Dialog
            } catch (_: IllegalStateException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    problemEnvelope("invalid_kind", "kind must be 'dialog' or 'inbox'."),
                )
            }

            val idempotencyKey = body.idempotencyKey?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val result = messages.create(
                id = UUID.randomUUID(),
                userId = parsedUserId,
                idempotencyKey = idempotencyKey,
                kind = kind,
                emoji = body.message.emoji,
                title = body.message.title.trim(),
                body = body.message.body.trim(),
                deepLink = body.message.deepLink?.takeUnless { it.isBlank() },
                expiresAt = body.message.expiresAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
            )
            call.respond(
                HttpStatusCode.OK,
                SendMessageResponse(
                    message = result.message.toAdminDto(),
                    outcome = if (result.wasAlreadyCreated) {
                        SendMessageOutcomeDto.AlreadyScheduled
                    } else {
                        SendMessageOutcomeDto.Scheduled
                    },
                    idempotencyKey = idempotencyKey,
                ),
            )
        }
    }
}

/**
 * Returns a (status, problem-envelope) pair describing the validation
 * failure, or `null` if the payload is good. Same checks both the
 * `POST /v1/admin/messages` body and the attached-message branch on
 * `POST /v1/admin/grant-chips`.
 */
private fun validateMessagePayload(
    payload: AdminMessagePayload,
): Pair<HttpStatusCode, Map<String, Map<String, String>>>? {
    if (payload.title.trim().isEmpty()) {
        return HttpStatusCode.BadRequest to
            problemEnvelope("invalid_title", "title must be a non-empty string.")
    }
    if (payload.title.length > MAX_MESSAGE_TITLE_LEN) {
        return HttpStatusCode.BadRequest to
            problemEnvelope("invalid_title", "title is too long (max $MAX_MESSAGE_TITLE_LEN chars).")
    }
    if (payload.body.trim().isEmpty()) {
        return HttpStatusCode.BadRequest to
            problemEnvelope("invalid_body_field", "body must be a non-empty string.")
    }
    if (payload.body.length > MAX_MESSAGE_BODY_LEN) {
        return HttpStatusCode.BadRequest to
            problemEnvelope("invalid_body_field", "body is too long (max $MAX_MESSAGE_BODY_LEN chars).")
    }
    payload.emoji?.let {
        if (it.length > MAX_MESSAGE_EMOJI_LEN) {
            return HttpStatusCode.BadRequest to
                problemEnvelope("invalid_emoji", "emoji is too long (max $MAX_MESSAGE_EMOJI_LEN chars).")
        }
    }
    return null
}

private const val MAX_MESSAGE_TITLE_LEN = 80
private const val MAX_MESSAGE_BODY_LEN = 500
private const val MAX_MESSAGE_EMOJI_LEN = 16

@OptIn(ExperimentalTime::class)
private fun UserMessage.toAdminDto(): AdminMessageDto = AdminMessageDto(
    id = id.toString(),
    userId = userId.value.toString(),
    kind = kind.wire,
    emoji = emoji,
    title = title,
    body = body,
    deepLink = deepLink,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    expiresAtEpochMs = expiresAt?.toEpochMilliseconds(),
)

private fun io.ktor.server.application.ApplicationCall.authenticatedAsAdmin(config: AdminConfig): Boolean {
    val token = config.apiToken?.takeUnless { it.isBlank() } ?: return false
    val presented = request.header("X-Admin-Token") ?: return false
    // Constant-time compare to avoid timing leaks. Length differences are
    // OK to short-circuit on — the attacker learns the expected length
    // either way and it's not load-bearing.
    if (presented.length != token.length) return false
    var mismatch = 0
    for (i in token.indices) mismatch = mismatch or (token[i].code xor presented[i].code)
    return mismatch == 0
}

@Serializable
private data class SweepResponse(
    val candidatesFound: Int,
    val deleted: Int,
    val failedToDelete: Int,
    val notConfigured: Boolean,
)

@Serializable
data class MessageSweepResponse(
    val ackedPurged: Int,
    val expiredUnackedPurged: Int,
    val total: Int,
)

@Serializable
private data class AdminRoomListResponse(
    val rooms: List<AdminRoomSummary>,
) {
    val totalRooms: Int get() = rooms.size
    val totalConnectedMembers: Int get() = rooms.sumOf { it.connectedCount }
}

@Serializable
data class GrantChipsRequest(
    val userId: String,
    val delta: Long,
    val reason: String,
    val idempotencyKey: String? = null,
    /** Optional in-app dialog to schedule alongside the grant. */
    val message: AdminMessagePayload? = null,
)

@Serializable
data class GrantChipsResponse(
    val balance: Long,
    val outcome: GrantChipsOutcomeDto,
    val idempotencyKey: String,
    /** The attached dialog, when the caller asked for one AND the grant
     *  was applied (a replay returns the existing chip outcome with
     *  message = null because the dialog was already scheduled the
     *  first time around). */
    val message: AdminMessageDto? = null,
)

@Serializable
enum class GrantChipsOutcomeDto {
    Applied,
    AlreadyApplied,
    InsufficientChips,
}

@Serializable
data class SendMessageRequest(
    val userId: String,
    val message: AdminMessagePayload,
    val idempotencyKey: String? = null,
    /**
     * `"dialog"` (default) or `"inbox"`. Determines whether the
     * message pops as a modal on next foreground (`dialog`) or sits
     * passively in the Notifications screen with a bottom-bar badge
     * (`inbox`).
     */
    val kind: String? = null,
)

@Serializable
data class SendMessageResponse(
    val message: AdminMessageDto,
    val outcome: SendMessageOutcomeDto,
    val idempotencyKey: String,
)

@Serializable
enum class SendMessageOutcomeDto {
    /** First time we've seen this (userId, idempotencyKey) — row inserted. */
    Scheduled,
    /** Replay — the existing row is returned unchanged. */
    AlreadyScheduled,
}

@Serializable
data class AdminMessagePayload(
    val emoji: String? = null,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    /**
     * Absolute epoch-ms when the message stops being delivered to the
     * user. Null = never expires. Reach for this on time-sensitive
     * notices (maintenance windows, day-of events).
     */
    val expiresAtEpochMs: Long? = null,
)

@Serializable
data class AdminMessageDto(
    val id: String,
    val userId: String,
    val kind: String,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
)

@Serializable
private data class AdminRoomSummary(
    val code: String,
    val hostUserId: String,
    val createdAtEpochMs: Long,
    val status: String,
    val maxSeats: Int,
    val memberCount: Int,
    val connectedCount: Int,
    val disconnectedCount: Int,
)

private fun problemEnvelope(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))
