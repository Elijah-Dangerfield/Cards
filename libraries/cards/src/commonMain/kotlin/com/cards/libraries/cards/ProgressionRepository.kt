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
    /** Total pot for the just-finished hand (sum of all pot awards). */
    val totalPot: Long = 0L,
    /**
     * True when the human folded AND the winner's final hand category would
     * have beaten the human's. Computed at hand-end with hindsight (the human's
     * hole cards + the dealt board), so it answers "was this the right fold?"
     * Null if the human didn't fold or there's no comparable hand.
     */
    val foldedHandWouldHaveLost: Boolean = false,
    /** True when the human won the pot without reaching showdown (everyone else folded). */
    val wonByFold: Boolean = false,
    /** True when the human went all-in at any point during the hand. */
    val humanWasAllIn: Boolean = false,
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
 * XP is **optimistic-local + server-reconciled** (Model 2, Phase 3): the
 * client computes XP per hand offline and accumulates it locally, then
 * [sync] flushes the pending ledger to the server, which holds the
 * authoritative `total_xp`. Lifetime hand counters stay client-local for now.
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
     * `description` is surfaced in the recent-XP feed so the user can tell
     * which achievement minted the row.
     */
    suspend fun applyAchievementXp(delta: Int, description: String? = null): XpEvent

    /**
     * Flush pending XP events to the server and reconcile `totalXp` to the
     * server's authoritative value. Idempotent + single-flight; safe to call
     * on cold boot, account switch, and warm foreground. An empty pending
     * ledger is a valid hydrate-only call (picks up cross-device XP).
     */
    suspend fun sync(): Result<Unit>

    /** Reset all progression state. Used by "Fresh Start" / debug menus. */
    suspend fun deleteAll()

    /**
     * Force `totalXp` to an absolute value. QA-only — bypasses the XP ledger
     * so it's safe to call from debug surfaces but never from gameplay code.
     */
    suspend fun debugSetTotalXp(totalXp: Long)
}

/** Read-only access to the XP ledger. Used by the detail sheet for activity views. */
interface XpEventRepository {
    fun observeRecent(limit: Int): Flow<List<XpEvent>>
    fun observeSince(sinceEpochMs: Long): Flow<List<XpEvent>>
}
