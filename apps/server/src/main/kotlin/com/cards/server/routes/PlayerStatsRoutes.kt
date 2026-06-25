package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.PlayerStatHand
import com.dangerfield.cards.server.domain.PlayerStats
import com.dangerfield.cards.server.domain.PlayerStatsRepository
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
 * Server-authoritative player-stats endpoints. The client records one row per
 * finished hand offline (hand counters + no-bust streak + per-bot win) and
 * flushes batches here; the server accumulates them idempotently and serves
 * the authoritative snapshot the stats screen + achievement predicates read.
 *
 * - `GET  /v1/me/stats` — the caller's own stats, lazy-creating at zero.
 * - `POST /v1/me/stats/sync` — flush a batch of per-hand contributions, return
 *   the post-sync snapshot + per-event outcomes.
 *
 * All require a valid Supabase JWT. Stats are private (no public read), unlike
 * play style.
 */
fun Route.playerStatsRoutes(repository: PlayerStatsRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/stats") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val stats = repository.findOrCreate(userId)
            call.respond(HttpStatusCode.OK, PlayerStatsResponse(stats = stats.toDto()))
        }

        rateLimit(RateLimitName(PROGRESSION_WRITE_LIMIT)) {
            post("/v1/me/stats/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<PlayerStatsSyncRequest>()

                val results = body.events.map { event ->
                    val outcome = repository.applyHand(userId, event.toDomain())
                    PlayerStatEventResultDto(
                        idempotencyKey = event.idempotencyKey,
                        outcome = if (outcome.wasAlreadyApplied) {
                            PlayerStatEventOutcomeDto.AlreadyApplied
                        } else {
                            PlayerStatEventOutcomeDto.Applied
                        },
                    )
                }

                val stats = repository.findOrCreate(userId)
                call.respond(
                    HttpStatusCode.OK,
                    PlayerStatsSyncResponse(stats = stats.toDto(), results = results),
                )
            }
        }
    }
}

private fun PlayerStats.toDto() = PlayerStatsDto(
    handsPlayed = handsPlayed,
    handsWon = handsWon,
    handsFolded = handsFolded,
    handsLostAtShowdown = handsLostAtShowdown,
    botHandsPlayed = botHandsPlayed,
    currentNoBustStreak = currentNoBustStreak,
    bestNoBustStreak = bestNoBustStreak,
    perBotWins = perBotWins,
)

private fun PlayerStatHandDto.toDomain() = PlayerStatHand(
    idempotencyKey = idempotencyKey,
    mode = mode,
    won = won,
    folded = folded,
    lostAtShowdown = lostAtShowdown,
    vsBot = vsBot,
    beatenBotId = beatenBotId,
    noBustStreak = noBustStreak,
)
