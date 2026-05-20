package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.WalletRepository
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
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
         * Frees seats whose socket has been dropped for at least
         * [AdminConfig.disconnectedRoomMemberTtlMinutes]. Intended to be
         * called frequently — every 1-5 minutes from an external cron —
         * because room seats unlike anon users are short-lived and a
         * blocked seat hurts UX immediately. Safe to call concurrently;
         * the room mutex serializes.
         *
         * Returns 200 with the sweep summary regardless of whether any
         * members got reaped — the caller logs the counts.
         */
        post("/sweep-disconnected-room-members") {
            if (!call.authenticatedAsAdmin(config)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val result = rooms.sweepDisconnected(
                maxIdle = config.disconnectedRoomMemberTtlMinutes.minutes,
            )
            call.respond(
                HttpStatusCode.OK,
                RoomSweepResponse(
                    membersReaped = result.membersReaped,
                    roomsReaped = result.roomsReaped,
                    roomsSeen = result.roomsSeen,
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
            val key = body.idempotencyKey?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val outcome = wallets.apply(
                userId = parsedUserId,
                idempotencyKey = key,
                delta = body.delta,
                reason = "admin_grant:$trimmedReason",
            )
            val (status, response) = when (outcome) {
                is ApplyOutcome.Applied -> HttpStatusCode.OK to GrantChipsResponse(
                    balance = outcome.balance,
                    outcome = if (outcome.wasAlreadyApplied) {
                        GrantChipsOutcomeDto.AlreadyApplied
                    } else {
                        GrantChipsOutcomeDto.Applied
                    },
                    idempotencyKey = key,
                )
                is ApplyOutcome.InsufficientChips -> HttpStatusCode.Conflict to GrantChipsResponse(
                    balance = outcome.balance,
                    outcome = GrantChipsOutcomeDto.InsufficientChips,
                    idempotencyKey = key,
                )
            }
            call.respond(status, response)
        }
    }
}

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
private data class RoomSweepResponse(
    val membersReaped: Int,
    val roomsReaped: Int,
    val roomsSeen: Int,
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
)

@Serializable
data class GrantChipsResponse(
    val balance: Long,
    val outcome: GrantChipsOutcomeDto,
    val idempotencyKey: String,
)

@Serializable
enum class GrantChipsOutcomeDto {
    Applied,
    AlreadyApplied,
    InsufficientChips,
}

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
