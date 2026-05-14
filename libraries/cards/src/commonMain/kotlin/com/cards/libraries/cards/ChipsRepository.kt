package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Owns the player's chip balance — currently a static value, but persisted
 * so home, shop, and future game surfaces all observe the same source.
 *
 * V1 behavior: lazy-seeds the balance to [STARTING_GRANT] on first read and
 * never moves it (no shop spend yet; bot games auto-rebuy from a separate
 * pool). Phase 3 multiplayer wires real win/loss deltas through `applyDelta`.
 */
interface ChipsRepository {

    /** Observable balance. Lazy-seeds on first read if the row doesn't exist. */
    fun observeBalance(): Flow<Long>

    /** One-shot read. Lazy-seeds on first read if the row doesn't exist. */
    suspend fun getBalance(): Long

    /** Adjust the balance by `delta` (positive or negative). */
    suspend fun applyDelta(delta: Long)

    /** Reset chips (used by "Fresh Start" / debug). */
    suspend fun deleteAll()

    companion object {
        /** Initial grant seeded on first launch. */
        const val STARTING_GRANT: Long = 10_000L
    }
}
