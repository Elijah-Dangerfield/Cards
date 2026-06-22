package com.dangerfield.cards.libraries.social

import kotlinx.coroutines.flow.Flow

/**
 * The caller's recently-played-with opponents — the social cold-start
 * surface (`RecentlyPlayedWithStrip` on Home). Backed by
 * `GET /v1/recent-opponents` (bare ids, newest-first) resolved against
 * `GET /v1/profiles` for the renderable display fields.
 *
 * Per the locked product rule, this shelf is the *only* place a user can
 * source someone to friend — every id here came from a shared multiplayer
 * hand the server recorded.
 */
interface RecentOpponentsRepository {

    /**
     * Reactive view of the resolved recent opponents, newest-first. Emits
     * the empty list until the first [refresh] lands; ids that fail to
     * resolve to a public profile are dropped rather than rendered blank.
     */
    fun observe(): Flow<List<RecentOpponentProfile>>

    /**
     * Fetch the recent-opponent ids and resolve them to public profiles,
     * updating [observe]. Best-effort — a network failure leaves the last
     * good value in place rather than clearing the shelf.
     */
    suspend fun refresh(limit: Int = DEFAULT_RECENT_OPPONENT_LIMIT)
}

/** Matches the server's default `limit` on `GET /v1/recent-opponents`. */
const val DEFAULT_RECENT_OPPONENT_LIMIT = 10

/**
 * A recent opponent resolved to its public, renderable identity. The id is
 * the Supabase user id — the value a friend request is sent against.
 */
data class RecentOpponentProfile(
    val id: String,
    val displayName: String,
    val avatarEmoji: String,
    /** Hex string, or null = "use the theme default". */
    val avatarBackgroundColorHex: String?,
)
