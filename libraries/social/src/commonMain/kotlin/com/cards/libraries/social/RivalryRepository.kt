package com.dangerfield.cards.libraries.social

import kotlinx.coroutines.flow.Flow

/**
 * The caller's running head-to-head records. Backed by `GET /v1/rivalries`
 * (bare opponent ids plus their scoreline) resolved against `GET /v1/profiles`
 * for the renderable fields, the same two-step the recently-played-with shelf
 * uses.
 *
 * **Why this exists.** Multiplayer is 64% of games started and 54% of all hands,
 * and almost none of it comes through matchmaking: it happens in private rooms
 * with people you already know. Half of all human pairings have played together
 * more than once and eleven pairs have shared six sessions or more, which is the
 * only social loop in the app that works, and nothing in the product
 * acknowledged it. Meanwhile the progression engine fired 1,855 achievement
 * celebrations in 30 days into 16 cosmetic equips, because none of it is
 * something another person can see.
 *
 * A rivalry is unfinished by construction. A daily bonus asks you to show up; a
 * scoreline you are losing makes you want to.
 *
 * **Deliberately not behind `social.enabled`.** That flag gates the friend graph
 * and presence, which wait on SOC-2. A rivalry needs no graph and grants no new
 * visibility: it shows a name you already saw at the table and a score you were
 * present for. It has its own kill switch (`rivalries.enabled`).
 */
interface RivalryRepository {

    /**
     * Reactive view of the resolved rivalries, most-recently-played first. Emits
     * the empty list until the first [refresh] lands; an opponent whose profile
     * fails to resolve is dropped rather than rendered nameless.
     */
    fun observe(): Flow<List<Rivalry>>

    /**
     * Fetch the rivalries and resolve their opponents to public profiles,
     * updating [observe]. Best-effort: a network failure leaves the last good
     * value in place rather than clearing the card.
     */
    suspend fun refresh(limit: Int = DEFAULT_RIVALRY_LIMIT)
}

/** Matches the server's default `limit` on `GET /v1/rivalries`. */
const val DEFAULT_RIVALRY_LIMIT = 10

/**
 * One opponent's record, resolved to something renderable.
 *
 * Directed: [handsWon] is always the viewer's and [opponentHandsWon] always
 * theirs, so a card never has to work out which way round the record is stored.
 */
data class Rivalry(
    val opponentId: String,
    val displayName: String,
    val avatarEmoji: String,
    /** Hex string, or null = "use the theme default". */
    val avatarBackgroundColorHex: String?,
    val handsTogether: Int,
    val handsWon: Int,
    val opponentHandsWon: Int,
    /** The biggest pot the viewer has taken with this opponent at the table. */
    val biggestPotWon: Long,
    val sessionsTogether: Int,
) {
    /** Who is up. `Even` covers a dead heat, which is its own thing worth saying. */
    val standing: Standing
        get() = when {
            handsWon > opponentHandsWon -> Standing.Ahead
            handsWon < opponentHandsWon -> Standing.Behind
            else -> Standing.Even
        }

    enum class Standing { Ahead, Behind, Even }
}
