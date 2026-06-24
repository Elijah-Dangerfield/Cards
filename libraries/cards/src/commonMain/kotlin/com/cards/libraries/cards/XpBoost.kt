package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/** Multiplier applied to every XP award while a boost is active. */
const val XP_BOOST_MULTIPLIER: Int = 2

/** Default boost window: 5 minutes. */
const val XP_BOOST_DEFAULT_DURATION_MS: Long = 5L * 60L * 1000L

/**
 * Catalog id + grant key for the chip-priced XP Boost shop product. The
 * boost is a **consumable** — buying it spends chips and extends the
 * [XpBoostRepository] window rather than granting an inventory row, so it's
 * re-buyable and never shows as "owned." The client routes a chip offer with
 * [XP_BOOST_GRANTS_KEY] to that consumable path instead of the normal
 * inventory redeem; the server seeds the matching row (see
 * `V55__xp_boost_product.sql`).
 */
const val XP_BOOST_PRODUCT_ID: String = "boost_xp_2x"
const val XP_BOOST_GRANTS_KEY: String = "boost.xp_2x"

/**
 * Snapshot of the user's XP boost — an owned **stash count** plus the active
 * **time window**. [ownedCount] is how many inactive boosts the user has bought
 * or been gifted but not yet lit; [expiresAtEpochMs] is the instant the live
 * window lapses (`null` if none is or has ever been active). "Active" is relative
 * to a clock, so callers pass `now` rather than the snapshot deciding for them —
 * a countdown UI re-reads it every tick off the same snapshot.
 */
data class XpBoostStatus(
    val expiresAtEpochMs: Long?,
    val ownedCount: Int = 0,
) {
    fun isActiveAt(nowEpochMs: Long): Boolean =
        expiresAtEpochMs != null && expiresAtEpochMs > nowEpochMs

    /** Remaining boost window in ms (0 if inactive). */
    fun remainingMsAt(nowEpochMs: Long): Long =
        if (isActiveAt(nowEpochMs)) expiresAtEpochMs!! - nowEpochMs else 0L

    /** The XP multiplier to apply at [nowEpochMs] — [XP_BOOST_MULTIPLIER] or 1. */
    fun multiplierAt(nowEpochMs: Long): Int =
        if (isActiveAt(nowEpochMs)) XP_BOOST_MULTIPLIER else 1

    /** True when the user has a stashed boost they could light right now. */
    fun canActivateAt(nowEpochMs: Long): Boolean = ownedCount > 0 && !isActiveAt(nowEpochMs)

    companion object {
        val None = XpBoostStatus(expiresAtEpochMs = null, ownedCount = 0)
    }
}

/**
 * Owns the persisted XP boost — a **stash count** of inactive boosts plus the
 * active **time window**. Offline-friendly by construction — the boost's only
 * effect is local XP math ([XpCalculator] reads [multiplier] when awarding a
 * hand), so there's no server round-trip.
 *
 * Buying or gifting a boost calls [grant] (it lands in the stash, **inactive**);
 * the user later lights one with [activate], which consumes one stashed boost and
 * opens the window. Boosts are a uniform 30-minute consumable — the stash is a
 * plain count, not a per-boost duration. Re-activating while active *extends* the
 * window rather than resetting it (though the UI blocks that path so a second
 * boost isn't burned while one's already running).
 */
interface XpBoostRepository {

    /** Live boost snapshot (stash count + window), updated whenever either changes. */
    fun observe(): Flow<XpBoostStatus>

    /** One-shot read of the current stash + window. */
    suspend fun status(): XpBoostStatus

    /** Add [count] inactive boosts to the stash (a shop buy or a level-up gift). */
    suspend fun grant(count: Int = 1)

    /**
     * Consume one stashed boost and open the window for [durationMs] — extending
     * from the current expiry (so a re-light stacks time) when already active,
     * else starting fresh from now. Returns `false` without mutating anything when
     * the stash is empty.
     */
    suspend fun activate(durationMs: Long = XP_BOOST_DEFAULT_DURATION_MS): Boolean

    /** The XP multiplier to apply right now — [XP_BOOST_MULTIPLIER] or 1. */
    suspend fun multiplier(): Int
}
