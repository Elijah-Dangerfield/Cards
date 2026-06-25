package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.achievements.AchievementCounters
import com.dangerfield.cards.libraries.achievements.HandFacts
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-authoritative player-stats aggregate for a user: the eight headline
 * counters + the no-bust streak (for the stats screen), plus [counters] — the
 * full materialized achievement-counter projection.
 *
 * Event-sourced (Model 2): the append-only `player_stat_events` ledger stores
 * the complete raw [HandFacts] of each finished hand, keyed `(userId,
 * idempotencyKey)`; re-applying a key is a no-op. The aggregate is the
 * materialized read model — [counters] is `AchievementCounters` folded over the
 * ledger by [AchievementCounters.fold], the same fold the client runs for
 * optimistic display. The eight headline fields are a denormalized view of
 * well-known counter keys, kept for the existing stats DTO.
 *
 * Because every counter is derived by the server from raw facts (never from a
 * client-sent snapshot), a reinstall can't reset or clobber progress: it sends
 * no new facts for already-applied hands.
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
    /** The full achievement-counter projection (`name -> value`). */
    val counters: AchievementCounters,
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
            counters = AchievementCounters.EMPTY,
            createdAt = now,
            updatedAt = now,
        )
    }
}

/** The per-bot win map ({botId -> wins}) projected out of the `wins_vs_bot_*` counter family. */
fun AchievementCounters.perBotWins(): Map<String, Long> {
    val prefix = AchievementCounters.winsVsBot("")
    return values.asSequence()
        .filter { it.key.startsWith(prefix) && it.value > 0 }
        .associate { it.key.removePrefix(prefix) to it.value }
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
     * Fold one finished hand's raw [HandFacts] into the aggregate idempotently.
     * Lazy-creates the row if missing. Re-applying the same
     * [HandFacts.idempotencyKey] returns `wasAlreadyApplied = true` and mutates
     * nothing.
     *
     * Implementations MUST take a transaction so the ledger row + the bumped
     * aggregate commit together or not at all.
     */
    suspend fun applyHand(userId: UserId, facts: HandFacts): ApplyPlayerStatOutcome

    /**
     * Wipe player-stats aggregate + ledger for a user. Called from the
     * `DELETE /v1/me` cascade so account-delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}
