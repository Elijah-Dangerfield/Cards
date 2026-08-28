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
    /** True when an XP boost doubled this hand award (cosmetic feed flag). */
    val wasBoosted: Boolean,
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

/** One XP award in a [ProgressionRepository.applyXpBatch] payload. */
data class XpEventInput(
    val idempotencyKey: String,
    val deltaXp: Long,
    val source: String,
    val mode: String,
    val handId: String?,
    val wasBoosted: Boolean = false,
)

/**
 * Result of [ProgressionRepository.applyXpBatch]. [appliedKeys] holds only the
 * keys this call actually committed — every other key in the batch was already
 * on the server (a replay) and moved nothing.
 */
data class ApplyXpBatchResult(
    val totalXp: Long,
    val appliedKeys: Set<String>,
)

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
     * Apply a whole batch of XP awards idempotently, lazy-creating the row if
     * missing. Each [XpEventInput.deltaXp] is clamped to `0..`[MAX_EVENT_XP]
     * before it's written — a cheap sanity backstop (XP is play-money /
     * low-stakes in V1; harden to server-derivation when stakes rise).
     * Duplicate keys *within* [events] collapse to their first occurrence.
     *
     * Implementations MUST apply the whole batch in ONE transaction, and its
     * cost must not scale with `events.size` in round trips — a client
     * flushing a week-old backlog is the case that broke prod (ENG-45).
     * [ApplyXpBatchResult.totalXp] moves by exactly the clamped sum of the
     * keys in [ApplyXpBatchResult.appliedKeys], so a full replay moves it by
     * zero.
     */
    suspend fun applyXpBatch(userId: UserId, events: List<XpEventInput>): ApplyXpBatchResult

    /**
     * Single-event convenience over [applyXpBatch]. Re-applying the same
     * [idempotencyKey] returns [ApplyXpOutcome.Applied] with
     * `wasAlreadyApplied = true` and the current total, mutating nothing.
     */
    suspend fun applyXp(
        userId: UserId,
        idempotencyKey: String,
        deltaXp: Long,
        source: String,
        mode: String,
        handId: String?,
        wasBoosted: Boolean = false,
    ): ApplyXpOutcome {
        val result = applyXpBatch(
            userId = userId,
            events = listOf(
                XpEventInput(
                    idempotencyKey = idempotencyKey,
                    deltaXp = deltaXp,
                    source = source,
                    mode = mode,
                    handId = handId,
                    wasBoosted = wasBoosted,
                ),
            ),
        )
        return ApplyXpOutcome.Applied(
            totalXp = result.totalXp,
            wasAlreadyApplied = idempotencyKey !in result.appliedKeys,
        )
    }

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

        /**
         * The clamp itself, so the write path and the response's per-key
         * running total can't drift apart on what an event was worth.
         */
        fun clampEventXp(deltaXp: Long): Long = deltaXp.coerceIn(0L, MAX_EVENT_XP)
    }
}
