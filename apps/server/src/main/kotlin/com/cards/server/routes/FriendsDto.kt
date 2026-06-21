package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire formats for the friend-graph endpoints.
 *
 * User ids are the Supabase `auth.users` UUIDs as strings — the same ids the
 * recently-played-with shelf surfaces, which is the only place a client is
 * allowed to source an id to friend.
 */
@Serializable
data class FriendRequestBody(
    /** The user being friended. */
    val userId: String,
)

/**
 * Result of sending a friend request. `state` is the resulting pair state:
 * `requested` (pending) or `accepted` (the other side had already requested,
 * so the request completed the friendship immediately).
 */
@Serializable
data class FriendRequestResult(
    val state: String,
)

@Serializable
data class FriendsResponse(
    val schemaVersion: Int = 1,
    /** The caller's accepted friends, most-recent-first. */
    val friends: List<String>,
)

@Serializable
data class FriendRequestsResponse(
    val schemaVersion: Int = 1,
    /** Senders of pending inbound requests to the caller, most-recent-first. */
    val requests: List<String>,
)
