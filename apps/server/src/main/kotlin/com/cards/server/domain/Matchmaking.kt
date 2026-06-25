package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.gameplay.RoomSettings
import java.util.UUID
import kotlin.math.abs

/**
 * Public-matchmaking domain types. The matchmaker is find-or-create, not a
 * queue: it seats a searcher into the best eligible open/public room within
 * their buy-in range, else opens a fresh public table for them (no bots yet —
 * we genuinely wait for real humans).
 */

/** Outcome of [RoomService.findOrJoinPublic]. Both carry the room to connect to;
 *  the distinction drives the created-vs-joined "is there organic density yet?"
 *  metric. */
sealed interface MatchmakingResult {
    val room: Room

    /** Seated into an existing eligible room (Open or Public). */
    data class Joined(override val room: Room) : MatchmakingResult

    /** No eligible room — opened a fresh Public table seated with just this player. */
    data class Created(override val room: Room) : MatchmakingResult
}

/**
 * Canonical buy-in tiers. New public tables snap their buy-in to one of these so
 * searchers with overlapping ranges converge on the *same* room instead of each
 * minting a slightly-different-stakes table that no one else ever matches. Join
 * still accepts any existing room whose buy-in falls in the searcher's range —
 * tiers only constrain creation.
 */
object BuyInTier {
    /** Ascending canonical stakes. Kept small + round so the lobby doesn't fragment. */
    val Canonical: List<Long> = listOf(1_000L, 5_000L, 25_000L, 100_000L)

    /**
     * The buy-in a freshly-created public table should use for a searcher's
     * `[min, max]` range. Prefers the canonical tier inside the range closest to
     * the default stake (so overlapping ranges cluster on the popular tier).
     *
     * The result is always inside `[min, max]`. When the range straddles a
     * canonical tier we return that tier; when it straddles none we take the
     * nearest canonical and clamp it into the range. Staying in-range is the
     * load-bearing property for convergence: the candidate filter matches an
     * existing room by `room.buyIn in min..max`, so a created table whose buy-in
     * fell *outside* the searcher's own range could never be re-joined by the
     * next searcher with that same range — both would keep minting fresh tables
     * (MP-15). A clamped buy-in is always a legal stake because the range itself
     * is validated to `RoomSettings.MIN_BUY_IN..MAX_BUY_IN` before we get here.
     */
    fun within(min: Long, max: Long): Long {
        val inRange = Canonical.filter { it in min..max }
        if (inRange.isNotEmpty()) {
            return inRange.minByOrNull { abs(it - RoomSettings.DEFAULT_BUY_IN) }!!
        }
        val nearest = Canonical.minByOrNull { minOf(abs(it - min), abs(it - max)) }!!
        return nearest.coerceIn(min, max)
    }
}

/**
 * Synthetic host for matchmaker-created public tables. No human owns a public
 * room — the server deals it — so its `hostUserId` is this fixed sentinel rather
 * than a player. It is never a *member* (never seated), has no auth.users row,
 * and being constant means the per-host room cap can't apply to public tables
 * (matchmaking uses a separate global cap instead).
 */
val SYSTEM_HOST_USER_ID: UserId = UserId(UUID(0L, 0L))
