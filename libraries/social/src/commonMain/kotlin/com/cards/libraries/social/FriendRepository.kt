package com.dangerfield.cards.libraries.social

import kotlinx.coroutines.flow.Flow

/**
 * The friend graph from the caller's side. Surfaces the outbound
 * "send a request" path plus the inbound inbox (pending requests +
 * accept / decline). The only legitimate source of an id to friend is the
 * recently-played-with shelf (see [RecentOpponentsRepository]); the server
 * enforces that with a `403 not_played_with` gate on send.
 *
 * Presence (online / last-seen) is a separate WS signal and not modelled here.
 */
interface FriendRepository {

    /**
     * Send a friend request to [userId]. The server auto-accepts when the
     * other side had already requested, so success splits into
     * [SendFriendRequestResult.Requested] vs [SendFriendRequestResult.Accepted].
     */
    suspend fun sendRequest(userId: String): SendFriendRequestResult

    /**
     * Reactive view of the pending *inbound* friend requests, resolved to
     * renderable public profiles, newest-first. Emits the empty list until the
     * first [refreshIncomingRequests] lands; ids that fail to resolve to a
     * public profile are dropped rather than rendered blank.
     */
    fun observeIncomingRequests(): Flow<List<FriendProfile>>

    /**
     * Fetch the pending inbound request ids and resolve them to public
     * profiles, updating [observeIncomingRequests]. Best-effort — a network
     * failure leaves the last good value in place rather than clearing the
     * inbox.
     */
    suspend fun refreshIncomingRequests()

    /**
     * Accept the pending request from [userId]. On success the request is
     * dropped from [observeIncomingRequests] optimistically so the row leaves
     * the inbox immediately.
     */
    suspend fun accept(userId: String): RespondToRequestResult

    /**
     * Decline the pending request from [userId]. On success the request is
     * dropped from [observeIncomingRequests] optimistically.
     */
    suspend fun decline(userId: String): RespondToRequestResult
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

/**
 * Outcome of [FriendRepository.accept] / [FriendRepository.decline]. A
 * `404 not_found` (the request vanished — the other side cancelled, or it was
 * already actioned) is treated as success from the inbox's point of view: the
 * row should leave either way.
 */
sealed interface RespondToRequestResult {
    /** The request was accepted/declined (or was already gone). */
    data object Ok : RespondToRequestResult

    /** Network failure or any unmapped error — the row stays in the inbox. */
    data class Error(val cause: Throwable) : RespondToRequestResult
}

/**
 * A friend (or pending requester) resolved to its public, renderable identity.
 * The id is the Supabase user id — the value an accept / decline is sent
 * against.
 */
data class FriendProfile(
    val id: String,
    val displayName: String,
    val avatarEmoji: String,
    /** Hex string, or null = "use the theme default". */
    val avatarBackgroundColorHex: String?,
)
