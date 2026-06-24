package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.PlayStyleAxes
import com.dangerfield.cards.server.domain.PlayStyleHand
import com.dangerfield.cards.server.domain.PlayStyleRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.toAxes
import com.dangerfield.cards.server.plugins.PROGRESSION_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Server-authoritative play-style endpoints. The client derives the four axes
 * (Tight / Aggro / Bluff / Patient) from real gameplay, records one row per
 * hand offline, and flushes batches here; the server sums the raw counters and
 * computes the axes on read (so the formula stays tunable — see
 * docs/decisions.md).
 *
 * - `GET  /v1/me/play-style` — the caller's own axes, lazy-creating at zero.
 * - `POST /v1/me/play-style/sync` — flush a batch of per-hand contributions,
 *   return the post-sync axes + per-event outcomes.
 * - `GET  /v1/users/{userId}/play-style` — another user's **public** axes, for
 *   the Opponent Style Reader. Summary counts only; no per-hand history.
 *
 * All require a valid Supabase JWT. The public read is auth-gated but not
 * relationship-gated: a play style is public info (the shop copy says as much),
 * and the only user ids a client can obtain are ones the server handed it (a
 * multiplayer seat roster).
 */
fun Route.playStyleRoutes(repository: PlayStyleRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/play-style") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val axes = repository.findOrCreateAggregate(userId).toAxes()
            call.respond(HttpStatusCode.OK, PlayStyleResponse(axes = axes.toDto()))
        }

        get("/v1/users/{userId}/play-style") {
            call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val target = call.parameters["userId"]?.toUserIdOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    playStyleProblem("invalid_user_id", "userId must be a valid UUID."),
                )
            val axes = repository.find(target)?.toAxes() ?: PlayStyleAxes.Empty
            call.respond(HttpStatusCode.OK, PlayStyleResponse(axes = axes.toDto()))
        }

        rateLimit(RateLimitName(PROGRESSION_WRITE_LIMIT)) {
            post("/v1/me/play-style/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<PlayStyleSyncRequest>()

                val results = body.events.map { event ->
                    val outcome = repository.applyHand(userId, event.toDomain())
                    PlayStyleEventResultDto(
                        idempotencyKey = event.idempotencyKey,
                        outcome = if (outcome.wasAlreadyApplied) {
                            PlayStyleEventOutcomeDto.AlreadyApplied
                        } else {
                            PlayStyleEventOutcomeDto.Applied
                        },
                    )
                }

                val axes = repository.findOrCreateAggregate(userId).toAxes()
                call.respond(
                    HttpStatusCode.OK,
                    PlayStyleSyncResponse(axes = axes.toDto(), results = results),
                )
            }
        }
    }
}

private fun String.toUserIdOrNull(): UserId? = try {
    takeIf { it.isNotBlank() }?.let { UserId.parse(it) }
} catch (_: IllegalArgumentException) {
    null
}

private fun playStyleProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

private fun PlayStyleAxes.toDto() = PlayStyleAxesDto(
    tight = tight,
    aggro = aggro,
    bluff = bluff,
    patient = patient,
    sampleSize = sampleSize,
)

private fun PlayStyleHandDto.toDomain() = PlayStyleHand(
    idempotencyKey = idempotencyKey,
    mode = mode,
    inBlind = inBlind,
    vpip = vpip,
    pfr = pfr,
    preflopFold = preflopFold,
    aggressiveActionCount = aggressiveActionCount,
    callActionCount = callActionCount,
    wentToShowdown = wentToShowdown,
    showdownBluff = showdownBluff,
)
