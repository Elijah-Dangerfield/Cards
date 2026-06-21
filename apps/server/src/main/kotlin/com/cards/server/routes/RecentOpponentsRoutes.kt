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
 * Recently-played-with endpoint. A thin HTTP/JSON shell over
 * [RecentOpponentsRepository].
 *
 * - `GET /v1/recent-opponents?limit=N` — the caller's most-recent human
 *   opponents, newest-first. `limit` is optional (default [DEFAULT_LIMIT],
 *   clamped to [MAX_LIMIT]).
 *
 * Returns bare user ids — the same shape as `GET /v1/friends`. The client
 * resolves display name / emoji / avatar from its own profile cache, and these
 * are the only ids the client is allowed to source a friend request from.
 */
fun Route.recentOpponentsRoutes(recentOpponents: RecentOpponentsRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/recent-opponents") {
            val me = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?.coerceIn(1, MAX_LIMIT)
                ?: DEFAULT_LIMIT
            call.respond(
                HttpStatusCode.OK,
                RecentOpponentsResponse(
                    opponents = recentOpponents.listRecent(me, limit).map { it.value.toString() },
                ),
            )
        }
    }
}

private const val DEFAULT_LIMIT = 10
private const val MAX_LIMIT = 50

@Serializable
data class RecentOpponentsResponse(
    val schemaVersion: Int = 1,
    /** The caller's recent human opponents, most-recent-first. */
    val opponents: List<String>,
)
