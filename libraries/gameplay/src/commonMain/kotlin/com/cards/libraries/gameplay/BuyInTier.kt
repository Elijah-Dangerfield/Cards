package com.dangerfield.cards.libraries.gameplay

import kotlin.math.abs

/**
 * Canonical buy-in tiers. New public tables snap their buy-in to one of these so
 * searchers with overlapping ranges converge on the *same* room instead of each
 * minting a slightly-different-stakes table that no one else ever matches. Join
 * still accepts any existing room whose buy-in falls in the searcher's range —
 * tiers only constrain creation.
 *
 * Shared between the matchmaker (server-authoritative table creation) and the
 * Find screen (client-side "what you'll get" preview), so the stake the client
 * shows before searching is the exact stake the server will snap to. Lives next
 * to [RoomSettings] as the single source of truth; the server imports it rather
 * than owning a private copy that could drift from the preview.
 */
object BuyInTier {
    /** Ascending canonical stakes. Kept small + round so the lobby doesn't fragment. */
    val Canonical: List<Long> = listOf(1_000L, 5_000L, 25_000L, 100_000L)

    /**
     * The tier overlapping ranges cluster on. Deliberately the lowest canonical
     * stake, NOT [RoomSettings.DEFAULT_BUY_IN] (5,000): with the 10,000-chip
     * starter grant and the 4× entry bar, the only canonical tier a fresh player
     * can actually sit at is 1,000 (needs 4,000). Anchoring the snap here keeps a
     * fresh searcher's default band landing on a table they can fund — the whole
     * point of the affordability fix. Kept separate from `DEFAULT_BUY_IN` (the
     * wire/host create fallback) so moving one never shifts the other's blast radius.
     */
    const val ANCHOR = 1_000L

    /**
     * The buy-in a freshly-created public table should use for a searcher's
     * `[min, max]` range. Prefers the canonical tier inside the range closest to
     * [ANCHOR] (so overlapping ranges cluster on the affordable tier).
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
            return inRange.minByOrNull { abs(it - ANCHOR) }!!
        }
        val nearest = Canonical.minByOrNull { minOf(abs(it - min), abs(it - max)) }!!
        return nearest.coerceIn(min, max)
    }

    /** One canonical step is a 5x jump (1k→5k→25k→100k). */
    const val STEP_RATIO = 5L

    /**
     * Whether two stakes are close enough to seat two lonely searchers together
     * (MP-34): the larger is within one canonical step of the smaller. Beyond one
     * step the stakes are too far apart to merge — a 1k player doesn't belong at a
     * 25k table. Used only to *rescue* a waiting singleton across a small gap; the
     * strict `buyIn in min..max` join still governs the normal path.
     */
    fun withinOneStep(a: Long, b: Long): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return lo > 0 && hi <= lo * STEP_RATIO
    }
}
