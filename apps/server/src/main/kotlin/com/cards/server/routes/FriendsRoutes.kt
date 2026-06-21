package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.FRIEND_REQUEST_LIMIT
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
 * Friend-graph endpoints. The graph is an unordered-pair model behind
 * [FriendRepository]; these routes are a thin HTTP/JSON shell over it.
 *
 * - `POST /v1/friends/requests` — send a request (rate-limited; a mutual
 *   request auto-accepts).
 * - `POST /v1/friends/requests/{id}/accept|decline|block` — respond to the
 *   request from / relationship with user `{id}`. Block dominates: it
 *   overwrites any existing relation and rejects further requests.
 * - `GET /v1/friends` — the caller's accepted friends.
 * - `GET /v1/friends/requests` — the caller's pending inbound requests.
 *
 * **The recently-played-with gate:** `POST /v1/friends/requests` rejects with
 * `403 not_played_with` unless [recentOpponents] shows the caller has shared a
 * multiplayer hand with the target — the product rule that the only way to
 * friend someone is the recently-played-with shelf. A self-request skips the
 * gate so it still surfaces the clearer `400 self_request`.
 */
fun Route.friendsRoutes(
    friends: FriendRepository,
    recentOpponents: RecentOpponentsRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        rateLimit(RateLimitName(FRIEND_REQUEST_LIMIT)) {
            post("/v1/friends/requests") {
                val me = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val target = call.receive<FriendRequestBody>().userId.toUserIdOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        friendsProblem("invalid_user_id", "User id must be a valid UUID."),
                    )

                if (me != target && !recentOpponents.hasPlayedWith(me, target)) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        friendsProblem(
                            "not_played_with",
                            "You can only friend someone you've played with.",
                        ),
                    )
                }

                when (friends.sendRequest(from = me, to = target)) {
                    SendRequestResult.Sent,
                    SendRequestResult.AlreadyRequested ->
                        call.respond(HttpStatusCode.OK, FriendRequestResult("requested"))
                    SendRequestResult.NowFriends,
                    SendRequestResult.AlreadyFriends ->
                        call.respond(HttpStatusCode.OK, FriendRequestResult("accepted"))
                    SendRequestResult.Blocked ->
                        call.respond(
                            HttpStatusCode.Forbidden,
                            friendsProblem("blocked", "This pair is blocked."),
                        )
                    SendRequestResult.SelfRequest ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            friendsProblem("self_request", "You can't friend yourself."),
                        )
                }
            }
        }

        post("/v1/friends/requests/{id}/accept") {
            respondToRequest(this.call) { me, other -> friends.accept(me, other) }
        }

        post("/v1/friends/requests/{id}/decline") {
            respondToRequest(this.call) { me, other -> friends.decline(me, other) }
        }

        post("/v1/friends/requests/{id}/block") {
            val me = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val other = call.parameters["id"]?.toUserIdOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    friendsProblem("invalid_user_id", "User id must be a valid UUID."),
                )
            friends.block(me = me, other = other)
            call.respond(HttpStatusCode.OK, FriendRequestResult("blocked"))
        }

        get("/v1/friends") {
            val me = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(
                HttpStatusCode.OK,
                FriendsResponse(friends = friends.listFriends(me).map { it.value.toString() }),
            )
        }

        get("/v1/friends/requests") {
            val me = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(
                HttpStatusCode.OK,
                FriendRequestsResponse(
                    requests = friends.listIncomingRequests(me).map { it.value.toString() },
                ),
            )
        }
    }
}

private suspend fun respondToRequest(
    call: io.ktor.server.application.ApplicationCall,
    action: suspend (me: UserId, other: UserId) -> RespondResult,
) {
    val me = call.userId() ?: return call.respond(HttpStatusCode.Unauthorized)
    val other = call.parameters["id"]?.toUserIdOrNull()
        ?: return call.respond(
            HttpStatusCode.BadRequest,
            friendsProblem("invalid_user_id", "User id must be a valid UUID."),
        )
    when (action(me, other)) {
        RespondResult.Ok -> call.respond(HttpStatusCode.OK, FriendRequestResult("ok"))
        RespondResult.NotFound ->
            call.respond(HttpStatusCode.NotFound, friendsProblem("not_found", "No such request."))
        RespondResult.Forbidden ->
            call.respond(HttpStatusCode.Forbidden, friendsProblem("forbidden", "Action not allowed."))
    }
}

private fun String.toUserIdOrNull(): UserId? = try {
    takeIf { it.isNotBlank() }?.let { UserId.parse(it) }
} catch (_: IllegalArgumentException) {
    null
}

private fun friendsProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))
