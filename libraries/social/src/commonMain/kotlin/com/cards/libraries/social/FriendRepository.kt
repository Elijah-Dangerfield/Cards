package com.dangerfield.cards.libraries.social

/**
 * The friend graph from the caller's side. V1 surfaces only the outbound
 * "send a request" path — the inbox / accept-decline / presence wiring lands
 * with the rest of the social graph. The only legitimate source of an id to
 * friend is the recently-played-with shelf (see [RecentOpponentsRepository]);
 * the server enforces that with a `403 not_played_with` gate.
 */
interface FriendRepository {

    /**
     * Send a friend request to [userId]. The server auto-accepts when the
     * other side had already requested, so success splits into
     * [SendFriendRequestResult.Requested] vs [SendFriendRequestResult.Accepted].
     */
    suspend fun sendRequest(userId: String): SendFriendRequestResult
}

/**
 * Outcome of [FriendRepository.sendRequest]. Every non-success case maps to a
 * distinct server status so the UI can tailor its message; from the
 * recently-played-with shelf the common terminal case is [Requested].
 */
sealed interface SendFriendRequestResult {
    /** Pending request created (or already pending) — the tile flips to "Sent". */
    data object Requested : SendFriendRequestResult

    /** The other side had already requested, so the friendship completed now. */
    data object Accepted : SendFriendRequestResult

    /**
     * `403` — the caller hasn't shared a multiplayer hand with the target, so
     * the request is refused. Also covers a blocked pair (the server returns
     * `403` for both); the block-specific UI isn't built yet.
     */
    data object NotPlayedWith : SendFriendRequestResult

    /** `400` — the caller tried to friend themselves. */
    data object SelfRequest : SendFriendRequestResult

    /** `429` — the friend-request rate limit tripped; try again later. */
    data object RateLimited : SendFriendRequestResult

    /** Network failure or any unmapped error. */
    data class Error(val cause: Throwable) : SendFriendRequestResult
}
