package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-authoritative XP total for a user, with the audit trail of applied
 * XP awards. The row is lazy-created on first contact (typically the first
 * `POST /v1/me/progression/sync`) with `total_xp = 0`; the client's flushed
 * events accumulate into it.
 *
 * Model 2 (optimistic-local + server-reconciled), mirroring [WalletRepository]:
 * the client computes XP per hand offline ([XpCalculator] stays client-side)
 * and the server **stores + caps** the deltas idempotently. The append-only
 * `xp_events` table is keyed by `(userId, idempotencyKey)`; re-applying the
 * same key is a no-op (replay-safe across retries + reinstalls).
 *
 * `level` is NOT stored — it's derived from [totalXp] by the client curve.
 */
@OptIn(ExperimentalTime::class)
data class UserProgression(
    val userId: UserId,
    val totalXp: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * One applied XP award. [deltaXp] is non-negative (XP only accrues) and was
 * clamped to [ProgressionRepository.MAX_EVENT_XP] before insert.
 */
@OptIn(ExperimentalTime::class)
data class XpEvent(
    val userId: UserId,
    val idempotencyKey: String,
    val deltaXp: Long,
    val source: String,
    val mode: String,
    val handId: String?,
    val appliedAt: Instant,
)

/**
 * Result of [ProgressionRepository.applyXp]. [Applied.totalXp] is the
 * post-apply authoritative total; [Applied.wasAlreadyApplied] is true on an
 * idempotent replay (the key was already on the server, no mutation).
 */
sealed interface ApplyXpOutcome {
    val totalXp: Long

    data class Applied(
        override val totalXp: Long,
        val wasAlreadyApplied: Boolean,
    ) : ApplyXpOutcome
}

/**
 * Result of [ProgressionRepository.findOrCreateResult]. [created] is `true`
 * only on the call that inserted the row. Provided for parity with the wallet
 * (no consumer today — XP has no starter grant to reveal).
 */
data class FindOrCreateProgressionResult(val progression: UserProgression, val created: Boolean)

@OptIn(ExperimentalTime::class)
interface ProgressionRepository {

    /**
     * Lazy-creates the progression row (`total_xp = 0`) if missing, then
     * returns it with whether this call created it. Idempotent.
     */
    suspend fun findOrCreateResult(userId: UserId): FindOrCreateProgressionResult

    /** Convenience wrapper for callers that don't care whether the row was new. */
    suspend fun findOrCreate(userId: UserId): UserProgression = findOrCreateResult(userId).progression

    /** Read-only lookup; null when no progression row exists yet. */
    suspend fun find(userId: UserId): UserProgression?

    /**
     * Apply an XP award idempotently. Lazy-creates the row if missing.
     * [deltaXp] is clamped to `0..`[MAX_EVENT_XP] before it's written — a
     * cheap sanity backstop (XP is play-money/low-stakes in V1; harden to
     * server-derivation when stakes rise). Re-applying the same
     * [idempotencyKey] returns [ApplyXpOutcome.Applied] with
     * `wasAlreadyApplied = true` and the current total, mutating nothing.
     *
     * Implementations MUST take a transaction so the ledger row + total
     * commit together or not at all.
     */
    suspend fun applyXp(
        userId: UserId,
        idempotencyKey: String,
        deltaXp: Long,
        source: String,
        mode: String,
        handId: String?,
    ): ApplyXpOutcome

    /** Recent ledger rows for a user, newest first, capped to [limit]. */
    suspend fun recentEvents(userId: UserId, limit: Int): List<XpEvent>

    /**
     * Wipe progression + ledger for a user. Called from the `DELETE /v1/me`
     * cascade so account-delete doesn't leave orphan XP rows.
     */
    suspend fun deleteAllForUser(userId: UserId)

    companion object {
        /**
         * Per-event sanity cap. Set far above any legitimate single award
         * (a hand tops out ~140 XP; the largest achievement award is the
         * LEGENDARY tier at 2,000) so it never clips real values — it only
         * rejects absurd/garbage deltas. Not a game-balance lever.
         */
        const val MAX_EVENT_XP: Long = 10_000L
    }
}
