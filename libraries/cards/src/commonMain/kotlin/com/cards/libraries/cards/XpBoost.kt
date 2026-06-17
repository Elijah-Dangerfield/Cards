package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/** Multiplier applied to every XP award while a boost is active. */
const val XP_BOOST_MULTIPLIER: Int = 2

/** Default boost window: 30 minutes. */
const val XP_BOOST_DEFAULT_DURATION_MS: Long = 30L * 60L * 1000L

/**
 * Snapshot of the user's **2× XP boost** — a time window, not an owned count.
 * [expiresAtEpochMs] is the instant the boost lapses (`null` if none is or has
 * ever been active). "Active" is relative to a clock, so callers pass `now`
 * rather than the snapshot deciding for them — a countdown UI re-reads it every
 * tick off the same snapshot.
 */
data class XpBoostStatus(
    val expiresAtEpochMs: Long?,
) {
    fun isActiveAt(nowEpochMs: Long): Boolean =
        expiresAtEpochMs != null && expiresAtEpochMs > nowEpochMs

    /** Remaining boost window in ms (0 if inactive). */
    fun remainingMsAt(nowEpochMs: Long): Long =
        if (isActiveAt(nowEpochMs)) expiresAtEpochMs!! - nowEpochMs else 0L

    /** The XP multiplier to apply at [nowEpochMs] — [XP_BOOST_MULTIPLIER] or 1. */
    fun multiplierAt(nowEpochMs: Long): Int =
        if (isActiveAt(nowEpochMs)) XP_BOOST_MULTIPLIER else 1

    companion object {
        val None = XpBoostStatus(expiresAtEpochMs = null)
    }
}

/**
 * Owns the persisted XP-boost window. Offline-friendly by construction — the
 * boost's only effect is local XP math ([XpCalculator] reads [multiplier] when
 * awarding a hand), so there's no server round-trip. Buying (chip spend rides
 * the wallet ledger separately) or gifting one calls [activate]; re-activating
 * while active *extends* the window rather than resetting it.
 */
interface XpBoostRepository {

    /** Live boost snapshot, updated whenever the window changes. */
    fun observe(): Flow<XpBoostStatus>

    /** One-shot read of the current window. */
    suspend fun status(): XpBoostStatus

    /**
     * Start a boost, or extend an already-active one by [durationMs]. Extends
     * from the current expiry (so a re-buy stacks time) when active, else from
     * now.
     */
    suspend fun activate(durationMs: Long = XP_BOOST_DEFAULT_DURATION_MS)

    /** The XP multiplier to apply right now — [XP_BOOST_MULTIPLIER] or 1. */
    suspend fun multiplier(): Int
}
