package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * Head-to-head records. A thin HTTP/JSON shell over
 * [RecentOpponentsRepository.listRivalries].
 *
 * - `GET /v1/rivalries?limit=N` — the caller's records against the people they
 *   keep playing, most-recently-played first. `limit` is optional (default
 *   [DEFAULT_LIMIT], clamped to [MAX_LIMIT]).
 *
 * Bare ids like every other list on this server: the client resolves the name
 * and avatar through `GET /v1/profiles`, so no surface here hands out a
 * directory of strangers. Every id in this response is someone the caller has
 * sat at a table with at least twice.
 *
 * **Not behind `social.enabled`.** That flag gates the friend graph and
 * presence, which are waiting on SOC-2. A rivalry is the caller's own match
 * history against someone they already played, needs no graph, and grants no
 * new visibility: it tells you a name you saw at the table and a score you were
 * present for.
 */
fun Route.rivalryRoutes(recentOpponents: RecentOpponentsRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/rivalries") {
            val me = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?.coerceIn(1, MAX_LIMIT)
                ?: DEFAULT_LIMIT
            call.respond(
                HttpStatusCode.OK,
                RivalriesResponse(
                    rivalries = recentOpponents.listRivalries(me, limit).map { rivalry ->
                        RivalryDto(
                            opponentId = rivalry.opponentId.value.toString(),
                            handsTogether = rivalry.handsTogether,
                            handsWon = rivalry.handsWon,
                            opponentHandsWon = rivalry.opponentHandsWon,
                            biggestPotWon = rivalry.biggestPotWon,
                            sessionsTogether = rivalry.sessionsTogether,
                            lastPlayedAtEpochMs = rivalry.lastPlayedAtEpochMs,
                        )
                    },
                ),
            )
        }
    }
}

private const val DEFAULT_LIMIT = 10
private const val MAX_LIMIT = 50

@Serializable
data class RivalriesResponse(
    val schemaVersion: Int = 1,
    val rivalries: List<RivalryDto>,
)

/**
 * Directed: [handsWon] is always the caller's and [opponentHandsWon] always the
 * opponent's, so the client renders a scoreline without knowing which way round
 * the row was stored.
 */
@Serializable
data class RivalryDto(
    val opponentId: String,
    val handsTogether: Int,
    val handsWon: Int,
    val opponentHandsWon: Int,
    val biggestPotWon: Long,
    val sessionsTogether: Int,
    val lastPlayedAtEpochMs: Long,
)
