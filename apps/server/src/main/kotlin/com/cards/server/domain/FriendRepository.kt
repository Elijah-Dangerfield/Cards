package com.dangerfield.cards.server.domain

/**
 * Server-of-record for the friend graph: pending requests, accepted
 * friendships, and blocks. Backs the friends strip, the requests inbox, and
 * the recently-played-with add-friend flow.
 *
 * The graph is an *unordered pair* model — one row per `{x, y}` canonicalised
 * so the smaller UUID is `user_a`. Callers pass user ids in any order; the
 * repository canonicalises. See `V61__friend_relations.sql` +
 * [com.dangerfield.cards.server.db.FriendRelationsTable].
 *
 * Direction the canonical pair can't carry is held by the row's `acted_by`:
 * the sender of a `requested` relation, the blocker of a `blocked` one. So an
 * inbound request to me is "a `requested` row I'm a member of whose `acted_by`
 * is the other member".
 *
 * Blocks dominate: a `blocked` pair rejects further requests/accepts, and
 * blocking overwrites any existing `accepted` relation.
 */
interface FriendRepository {

    /**
     * [from] asks to friend [to]. Returns the resulting state of the pair:
     * a fresh request, a no-op replay of an existing request, an auto-accept
     * when [to] had already requested [from] (mutual requests = friends), an
     * already-friends no-op, or a rejection because the pair is blocked.
     */
    suspend fun sendRequest(from: UserId, to: UserId): SendRequestResult

    /**
     * [me] accepts a pending request from [other]. Succeeds only when a
     * `requested` row exists with [other] as the sender; idempotent when the
     * pair is already accepted.
     */
    suspend fun accept(me: UserId, other: UserId): RespondResult

    /**
     * [me] declines (or cancels) a pending request involving [other],
     * deleting the row. Applies only to `requested` rows.
     */
    suspend fun decline(me: UserId, other: UserId): RespondResult

    /**
     * [me] blocks [other]. Always succeeds: overwrites any existing relation
     * (including an accepted friendship) with a `blocked` row whose `acted_by`
     * is [me].
     */
    suspend fun block(me: UserId, other: UserId)

    /** The accepted friends of [userId], most-recent-first. */
    suspend fun listFriends(userId: UserId): List<UserId>

    /**
     * The senders of pending inbound requests to [userId], most-recent-first
     * (requests [userId] can accept/decline).
     */
    suspend fun listIncomingRequests(userId: UserId): List<UserId>

    /**
     * Wipe every relation [userId] is a member of, from either side. Called
     * from the `DELETE /v1/me` cascade so account-delete doesn't strand half
     * a pair.
     */
    suspend fun deleteAllForUser(userId: UserId)
}

/** Outcome of [FriendRepository.sendRequest]. */
enum class SendRequestResult {
    /** A new pending request was created. */
    Sent,

    /** [from] had already requested [to]; no change. */
    AlreadyRequested,

    /** [to] had already requested [from]; the pair is now accepted. */
    NowFriends,

    /** The pair was already accepted; no change. */
    AlreadyFriends,

    /** The pair is blocked; the request is rejected. */
    Blocked,

    /** [from] and [to] are the same user; rejected. */
    SelfRequest,
}

/** Outcome of [FriendRepository.accept] / [FriendRepository.decline]. */
enum class RespondResult {
    /** The action applied (or was a no-op replay of the same end state). */
    Ok,

    /** No relation matched the action (e.g. accepting a non-existent request). */
    NotFound,

    /** The action is not allowed (a blocked pair, or accepting your own request). */
    Forbidden,
}
