package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * The user's server-authoritative lifetime player stats — the cumulative hand
 * counters, the no-bust streak (current + best), and the per-bot win map.
 *
 * The server is the source of truth: it accumulates the per-hand contributions
 * and folds the streak. The client caches the last synced snapshot so Stats
 * renders instantly and offline, and writes a local outbox row per hand so
 * progress survives a flaky network. Unlike [PlayStyleAxes] these are raw
 * counts, not a derived shape, so the client may read them directly.
 */
data class PlayerStats(
    val handsPlayed: Long,
    val handsWon: Long,
    val handsFolded: Long,
    val handsLostAtShowdown: Long,
    val botHandsPlayed: Long,
    val currentNoBustStreak: Long,
    val bestNoBustStreak: Long,
    val perBotWins: Map<String, Long>,
) {
    companion object {
        val Empty = PlayerStats(
            handsPlayed = 0,
            handsWon = 0,
            handsFolded = 0,
            handsLostAtShowdown = 0,
            botHandsPlayed = 0,
            currentNoBustStreak = 0,
            bestNoBustStreak = 0,
            perBotWins = emptyMap(),
        )
    }
}

/**
 * One finished hand's stat signals, from the human's perspective. The room
 * feature builds this at hand-end; [PlayerStatsRepository.recordHand] writes it
 * to the outbox to be flushed and accumulated into the server aggregate.
 *
 * [noBustStreak] is the client-computed streak length *after* this hand —
 * streaks are order-dependent so the client carries the snapshot and the server
 * folds latest-current + running-max-best.
 */
data class PlayerStatHandSummary(
    val handId: String,
    val mode: XpMode,
    val won: Boolean,
    val folded: Boolean,
    val lostAtShowdown: Boolean,
    val vsBot: Boolean,
    /** Bot beaten this hand, when [won] && [vsBot]; null otherwise. */
    val beatenBotId: String?,
    val noBustStreak: Long,
)

/**
 * Owns the user's server-authoritative player stats. Model 2 (optimistic-local
 * + server-reconciled), mirroring [PlayStyleRepository]: [recordHand] appends
 * one outbox row per hand offline; [sync] flushes the batch and caches the
 * server's authoritative snapshot.
 */
interface PlayerStatsRepository {

    /** The user's cached stats; null until the first sync populates them. */
    fun observeStats(): Flow<PlayerStats?>

    suspend fun getStats(): PlayerStats?

    /** Append one finished hand's signals to the outbox. */
    suspend fun recordHand(summary: PlayerStatHandSummary)

    /**
     * Flush pending hands to the server and cache the returned snapshot.
     * Idempotent + single-flight; an empty outbox is a valid hydrate-only call.
     */
    suspend fun sync(): Result<Unit>

    /** Reset all local player-stats state. Used by "Fresh Start" / debug menus. */
    suspend fun deleteAll()
}
