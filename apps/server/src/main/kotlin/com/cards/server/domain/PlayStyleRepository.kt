package com.dangerfield.cards.server.domain

import kotlin.math.max
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-authoritative play-style aggregate for a user. Rolling sums of the
 * per-hand counters the client flushes; the four axes are derived from these
 * by [toAxes] at read time and never stored, so the count → axis formula can
 * be re-tuned without an app release (see docs/decisions.md).
 *
 * Model 2 (optimistic-local + server-reconciled), mirroring [ProgressionRepository]:
 * the client records one row per hand offline and flushes batches; the server
 * accumulates them idempotently. The append-only `play_style_events` ledger is
 * keyed `(userId, idempotencyKey)`; re-applying the same key is a no-op.
 */
@OptIn(ExperimentalTime::class)
data class UserPlayStyleAggregate(
    val userId: UserId,
    val handsDealt: Long,
    val handsDealtNonBlind: Long,
    val vpipCount: Long,
    val pfrCount: Long,
    val preflopFoldCount: Long,
    val aggressiveActionCount: Long,
    val callActionCount: Long,
    val showdownCount: Long,
    val showdownBluffCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The four normalized (0..1) play-style axes plus the sample size that backs
 * them. The client renders these on the radar and gates the display on
 * [sampleSize] (showing a "keep playing" state below the threshold).
 */
data class PlayStyleAxes(
    val tight: Double,
    val aggro: Double,
    val bluff: Double,
    val patient: Double,
    /** Hands dealt — the sample the axes are computed from. */
    val sampleSize: Long,
) {
    companion object {
        /** Axes for a user with no recorded hands. */
        val Empty = PlayStyleAxes(tight = 0.0, aggro = 0.0, bluff = 0.0, patient = 0.0, sampleSize = 0L)
    }
}

/**
 * Derive the four axes from the rolling counters:
 *
 * - **Tight** = `1 − VPIP`, VPIP = voluntarily-put-money-in rate over hands
 *   the player wasn't only blind-posting in.
 * - **Aggro** = aggression factor `AF = aggressive ÷ calls`, squashed to 0..1
 *   via `AF / (AF + 2)` so a balanced AF≈1 lands near the middle.
 * - **Bluff** = rate of betting/raising into a showdown with a weak hand & losing.
 * - **Patient** = preflop fold-discipline (fold rate when not in a blind).
 *
 * Empty/low-sample aggregates fall out to low values; the client gates display
 * on [PlayStyleAxes.sampleSize], so the exact zero-sample shape doesn't matter.
 */
fun UserPlayStyleAggregate.toAxes(): PlayStyleAxes {
    val nonBlind = max(1L, handsDealtNonBlind).toDouble()
    val vpip = vpipCount / nonBlind
    val af = aggressiveActionCount.toDouble() / max(1L, callActionCount)
    val bluff = showdownBluffCount.toDouble() / max(1L, showdownCount)
    val patient = preflopFoldCount / nonBlind
    return PlayStyleAxes(
        tight = (1.0 - vpip).coerceIn(0.0, 1.0),
        aggro = (af / (af + 2.0)).coerceIn(0.0, 1.0),
        bluff = bluff.coerceIn(0.0, 1.0),
        patient = patient.coerceIn(0.0, 1.0),
        sampleSize = handsDealt,
    )
}

/**
 * Per-hand play-style contribution the client flushes. All fields are the raw
 * signals for one finished hand; the server folds them into the aggregate.
 */
data class PlayStyleHand(
    val idempotencyKey: String,
    val mode: String,
    val inBlind: Boolean,
    val vpip: Boolean,
    val pfr: Boolean,
    val preflopFold: Boolean,
    val aggressiveActionCount: Int,
    val callActionCount: Int,
    val wentToShowdown: Boolean,
    val showdownBluff: Boolean,
)

/**
 * Result of [PlayStyleRepository.applyHand]. [wasAlreadyApplied] is true on an
 * idempotent replay (the key was already on the server, no mutation).
 */
data class ApplyPlayStyleOutcome(val wasAlreadyApplied: Boolean)

/**
 * Result of [PlayStyleRepository.applyHandBatch]. [appliedKeys] holds only the
 * keys this call actually committed — every other key in the batch was already
 * on the server (a replay) and moved nothing. [aggregate] is the post-batch
 * aggregate, so answering with the axes costs no extra read.
 */
@OptIn(ExperimentalTime::class)
data class ApplyPlayStyleBatchResult(
    val aggregate: UserPlayStyleAggregate,
    val appliedKeys: Set<String>,
)

@OptIn(ExperimentalTime::class)
interface PlayStyleRepository {

    /**
     * Lazy-creates the aggregate row (all zero) if missing and returns it.
     * Idempotent.
     */
    suspend fun findOrCreateAggregate(userId: UserId): UserPlayStyleAggregate

    /** Read-only lookup; null when no aggregate row exists yet. */
    suspend fun find(userId: UserId): UserPlayStyleAggregate?

    /**
     * Fold a whole batch of finished hands into the aggregate idempotently,
     * lazy-creating the row if missing. Duplicate keys *within* [hands]
     * collapse to their first occurrence, and a key already on the server
     * moves nothing.
     *
     * Implementations MUST apply the whole batch in ONE transaction, so the
     * ledger rows + the bumped aggregate commit together or not at all — and
     * its cost must not scale with `hands.size` in round trips. A client
     * flushing a week-old backlog is the case that broke prod (ENG-47).
     */
    suspend fun applyHandBatch(userId: UserId, hands: List<PlayStyleHand>): ApplyPlayStyleBatchResult

    /**
     * Single-hand convenience over [applyHandBatch]. Re-applying the same
     * [PlayStyleHand.idempotencyKey] returns `wasAlreadyApplied = true` and
     * mutates nothing.
     */
    suspend fun applyHand(userId: UserId, hand: PlayStyleHand): ApplyPlayStyleOutcome {
        val result = applyHandBatch(userId, listOf(hand))
        return ApplyPlayStyleOutcome(wasAlreadyApplied = hand.idempotencyKey !in result.appliedKeys)
    }

    /**
     * Wipe play-style aggregate + ledger for a user. Called from the
     * `DELETE /v1/me` cascade so account-delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}
