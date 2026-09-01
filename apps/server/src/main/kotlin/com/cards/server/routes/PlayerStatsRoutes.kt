package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.achievements.HandFacts
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
 * - `GET  /v1/me/player-stats` — the caller's own stats, lazy-creating at zero.
 * - `POST /v1/me/player-stats/sync` — flush a batch of per-hand contributions,
 *   return the post-sync snapshot + per-event outcomes.
 *
 * The path is `player-stats`, not `stats`: `GET /v1/me/stats` already serves the
 * lifetime distinct-opponents read (see [meRoutes]), so these endpoints carve
 * out their own namespace rather than shadow it.
 *
 * All require a valid Supabase JWT. Stats are private (no public read), unlike
 * play style.
 */
fun Route.playerStatsRoutes(repository: PlayerStatsRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/player-stats") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val stats = repository.findOrCreate(userId)
            call.respond(HttpStatusCode.OK, PlayerStatsResponse(stats = stats.toDto()))
        }

        rateLimit(RateLimitName(PROGRESSION_WRITE_LIMIT)) {
            post("/v1/me/player-stats/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<PlayerStatsSyncRequest>()

                // One transaction for the whole payload, folded in the order the
                // client sent it. An empty batch is the "hydrate my stats"
                // pulse, and still lazy-creates the row.
                val batch = repository.applyHandBatch(userId, body.events.map { it.toFacts() })

                // A key the batch committed reads Applied exactly once — a
                // repeat of it inside the same payload is a replay like any
                // other, same as when the hands went one at a time.
                val counted = mutableSetOf<String>()
                val results = body.events.map { event ->
                    val applied = event.idempotencyKey in batch.appliedKeys &&
                        counted.add(event.idempotencyKey)
                    PlayerStatEventResultDto(
                        idempotencyKey = event.idempotencyKey,
                        outcome = if (applied) {
                            PlayerStatEventOutcomeDto.Applied
                        } else {
                            PlayerStatEventOutcomeDto.AlreadyApplied
                        },
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    PlayerStatsSyncResponse(stats = batch.stats.toDto(), results = results),
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
    achievementCounters = counters.values,
)

private fun PlayerStatHandDto.toFacts() = HandFacts(
    idempotencyKey = idempotencyKey,
    mode = mode,
    won = won,
    folded = folded,
    lostAtShowdown = lostAtShowdown,
    vsBot = vsBot,
    beatenBotId = beatenBotId,
    // A client that doesn't send `busted` still drives the streak correctly:
    // its no-bust streak resets to 0 exactly on a bust.
    busted = busted ?: (noBustStreak == 0L),
    botDifficulty = botDifficulty,
    startStack = startStack,
    endStack = endStack,
    bigBlind = bigBlind,
    potTotal = potTotal,
    wasAllIn = wasAllIn,
    wonByFold = wonByFold,
    bustsDealt = bustsDealt,
    foldedWouldHaveLost = foldedWouldHaveLost,
    handStrengthShown = handStrengthShown,
)
