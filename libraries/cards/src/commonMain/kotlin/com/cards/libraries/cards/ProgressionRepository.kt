package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Summary of one finished hand, from the human's perspective. The fields
 * here are exactly what `XpCalculator` needs — repository implementations
 * are responsible for translating engine events into this shape.
 *
 * `wonPot` is captured for stats only. The XP formula must not branch on it,
 * by design (see docs/decisions.md 2026-05-14).
 */
data class HandResultSummary(
    val handId: String,
    val mode: XpMode,
    val wasFold: Boolean,
    val reachedShowdown: Boolean,
    val wonPot: Boolean,
    val chipsCommitted: Long,
    val bigBlind: Long,
    /** Hand category at showdown. Null if the user folded before showdown. */
    val handCategory: HandCategoryGrade?,
)

/**
 * Compact representation of [com.dangerfield.cards.libraries.gameplay.HandCategory]
 * for the cards domain layer. We don't depend on `:libraries:gameplay` from here
 * to keep the dependency direction one-way; callers translate to/from the engine
 * enum at the boundary.
 *
 * Ordinal order (worst → best) matches the engine's hand category strength so
 * `ordinal + 1` is a fair "1..10" strength bonus.
 */
enum class HandCategoryGrade {
    HighCard,
    Pair,
    TwoPair,
    ThreeOfAKind,
    Straight,
    Flush,
    FullHouse,
    FourOfAKind,
    StraightFlush,
    RoyalFlush,
}

/**
 * Owns lifetime XP + hand counters.
 *
 * Local-only in V1 (bots earn XP on-device); Phase 3 swaps the backing store
 * for a server-authoritative ledger. The interface stays the same.
 */
interface ProgressionRepository {

    fun observeProgression(): Flow<Progression>

    suspend fun getProgression(): Progression

    /**
     * Compute XP from `summary`, append ledger rows, and update lifetime
     * counters atomically. Returns the events that were written.
     */
    suspend fun awardForHand(summary: HandResultSummary): List<XpEvent>

    /**
     * Add `delta` XP from a non-hand source — typically an unlocked
     * achievement. Records one ledger row with [XpSource.ACHIEVEMENT] and
     * the current mode-agnostic timestamp. Hand counters are not touched.
     */
    suspend fun applyAchievementXp(delta: Int): XpEvent

    /** Reset all progression state. Used by "Fresh Start" / debug menus. */
    suspend fun deleteAll()
}

/** Read-only access to the XP ledger. Used by the detail sheet for activity views. */
interface XpEventRepository {
    fun observeRecent(limit: Int): Flow<List<XpEvent>>
    fun observeSince(sinceEpochMs: Long): Flow<List<XpEvent>>
}
