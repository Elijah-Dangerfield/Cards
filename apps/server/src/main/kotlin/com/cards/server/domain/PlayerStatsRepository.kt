package com.dangerfield.cards.server.domain

import kotlin.math.max
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-authoritative player-stats aggregate for a user. Cumulative hand
 * counters, the no-bust streak (current + best), and a per-bot win map.
 *
 * Model 2 (optimistic-local + server-reconciled), mirroring
 * [PlayStyleRepository]: the client records one row per finished hand offline
 * and flushes batches; the server accumulates them idempotently. The
 * append-only `player_stat_events` ledger is keyed `(userId, idempotencyKey)`;
 * re-applying the same key is a no-op.
 *
 * Stats are the source of truth for the stats screen, and achievements read
 * their progress from these counters (rather than carrying their own numbers),
 * so adding an achievement later points at an existing counter with no data
 * migration.
 */
@OptIn(ExperimentalTime::class)
data class PlayerStats(
    val userId: UserId,
    val handsPlayed: Long,
    val handsWon: Long,
    val handsFolded: Long,
    val handsLostAtShowdown: Long,
    val botHandsPlayed: Long,
    val currentNoBustStreak: Long,
    val bestNoBustStreak: Long,
    /** Per-bot win counts, keyed by bot id. Empty when no bot beaten yet. */
    val perBotWins: Map<String, Long>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /** Stats for a user with no recorded hands. */
        fun empty(userId: UserId, now: Instant) = PlayerStats(
            userId = userId,
            handsPlayed = 0,
            handsWon = 0,
            handsFolded = 0,
            handsLostAtShowdown = 0,
            botHandsPlayed = 0,
            currentNoBustStreak = 0,
            bestNoBustStreak = 0,
            perBotWins = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
    }
}

/**
 * Per-hand stat contribution the client flushes. All fields are the raw
 * signals for one finished hand; the server folds them into the aggregate.
 *
 * [noBustStreak] is the client's running no-bust streak *after* this hand —
 * streaks are order-dependent so the server can't re-derive them by summing.
 * The aggregate takes the latest applied value as the current streak and the
 * running max as the best.
 */
data class PlayerStatHand(
    val idempotencyKey: String,
    val mode: String,
    val won: Boolean,
    val folded: Boolean,
    val lostAtShowdown: Boolean,
    val vsBot: Boolean,
    /** Bot the player beat this hand, when [won] && [vsBot]; null otherwise. */
    val beatenBotId: String?,
    val noBustStreak: Long,
)

/**
 * Fold one finished hand's signals into [PlayerStats]. The streak fields take
 * the event's snapshot (latest-wins for current, running max for best); every
 * other counter accumulates.
 */
fun PlayerStats.applying(hand: PlayerStatHand): PlayerStats {
    val beatenBot = hand.beatenBotId?.takeIf { hand.won && hand.vsBot }
    val nextPerBotWins = if (beatenBot != null) {
        perBotWins + (beatenBot to ((perBotWins[beatenBot] ?: 0L) + 1))
    } else {
        perBotWins
    }
    return copy(
        handsPlayed = handsPlayed + 1,
        handsWon = handsWon + if (hand.won) 1 else 0,
        handsFolded = handsFolded + if (hand.folded) 1 else 0,
        handsLostAtShowdown = handsLostAtShowdown + if (hand.lostAtShowdown) 1 else 0,
        botHandsPlayed = botHandsPlayed + if (hand.vsBot) 1 else 0,
        currentNoBustStreak = hand.noBustStreak,
        bestNoBustStreak = max(bestNoBustStreak, hand.noBustStreak),
        perBotWins = nextPerBotWins,
    )
}

/**
 * Result of [PlayerStatsRepository.applyHand]. [wasAlreadyApplied] is true on
 * an idempotent replay (the key was already on the server, no mutation).
 */
data class ApplyPlayerStatOutcome(val wasAlreadyApplied: Boolean)

@OptIn(ExperimentalTime::class)
interface PlayerStatsRepository {

    /**
     * Lazy-creates the stats row (all zero) if missing and returns it.
     * Idempotent.
     */
    suspend fun findOrCreate(userId: UserId): PlayerStats

    /** Read-only lookup; null when no stats row exists yet. */
    suspend fun find(userId: UserId): PlayerStats?

    /**
     * Fold one finished hand into the aggregate idempotently. Lazy-creates the
     * row if missing. Re-applying the same [PlayerStatHand.idempotencyKey]
     * returns `wasAlreadyApplied = true` and mutates nothing.
     *
     * Implementations MUST take a transaction so the ledger row + the bumped
     * aggregate commit together or not at all.
     */
    suspend fun applyHand(userId: UserId, hand: PlayerStatHand): ApplyPlayerStatOutcome

    /**
     * Wipe player-stats aggregate + ledger for a user. Called from the
     * `DELETE /v1/me` cascade so account-delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}
