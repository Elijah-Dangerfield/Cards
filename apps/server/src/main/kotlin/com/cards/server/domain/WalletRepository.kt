package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-authoritative chip balance for a user, with the audit trail of
 * applied chip movements. The wallet row is lazy-created on first read
 * (typically `GET /v1/me/wallet`) with the starter grant from
 * [Wallet.STARTER_GRANT]; subsequent calls return the persisted balance.
 *
 * The append-only `wallet_events` table is keyed by `(userId,
 * idempotencyKey)`. Re-applying the same idempotency key is a no-op
 * (the second [apply] call returns the existing balance + skips writing
 * the duplicate event). This is how the client can retry an upload
 * after a flaky network without double-applying a chip grant.
 */
@OptIn(ExperimentalTime::class)
data class Wallet(
    val userId: UserId,
    val balance: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /** Chip count granted on first contact (lazy-create). */
        const val STARTER_GRANT: Long = 10_000L

        /**
         * One-time grant when the user's wallet first hits zero —
         * "soft bust protection." Mirrors product-spec.md §4.1: no
         * timer, no claim prompt, lifetime-once per user (keyed off
         * [BUST_PROTECTION_KEY] in the ledger, so re-detecting the
         * zero state is a no-op).
         */
        const val BUST_PROTECTION_GRANT: Long = 1_000L

        /**
         * Wallet-event idempotency key for the bust-protection grant.
         * Stable across the user's lifetime so retries / re-detected
         * zero states collapse to a single ledger row.
         */
        const val BUST_PROTECTION_KEY: String = "bust_protection_v1"

        /** Ledger reason for the bust-protection grant. */
        const val BUST_PROTECTION_REASON: String = "bust_protection"

        /**
         * Window over which the welcome-week daily grant runs, counted
         * in whole days since the wallet's `createdAt`. Day 0 is the
         * first contact; day [WELCOME_WEEK_DAYS] - 1 is the last day
         * with an eligible grant. Mirrors product-spec.md §4.1: silent
         * 500/day for the first 7 days post-install / sign-up.
         */
        const val WELCOME_WEEK_DAYS: Int = 7

        /** Per-day chip count granted by the welcome-week schedule. */
        const val WELCOME_WEEK_DAILY_GRANT: Long = 500L

        /**
         * Idempotency-key prefix for the welcome-week daily grants. The
         * full key is `${WELCOME_WEEK_KEY_PREFIX}{day}_v1` where `day`
         * is the day index `0..WELCOME_WEEK_DAYS-1`. Lifetime-once per
         * (user, day).
         */
        const val WELCOME_WEEK_KEY_PREFIX: String = "welcome_week_day_"

        /** Ledger reason for welcome-week grants. */
        const val WELCOME_WEEK_REASON: String = "welcome_week"
    }
}

/**
 * One applied chip movement. `delta` can be negative (debit) — the repo
 * rejects deltas that would push the wallet balance below zero by
 * returning [ApplyOutcome.InsufficientChips] without writing the row.
 */
@OptIn(ExperimentalTime::class)
data class WalletEvent(
    val userId: UserId,
    val idempotencyKey: String,
    val delta: Long,
    val reason: String,
    val appliedAt: Instant,
)

/**
 * Result of [WalletRepository.apply].
 *
 * - [Applied] — the event was committed (or already-applied; see
 *   [Applied.wasAlreadyApplied]). [Applied.balance] is the post-apply
 *   wallet balance.
 * - [InsufficientChips] — a negative delta would have driven the
 *   balance below zero. Server returns the unchanged balance and skips
 *   the ledger write. Client surfaces a soft message; the local
 *   balance is the source-of-truth on what to display while we
 *   reconcile.
 */
@OptIn(ExperimentalTime::class)
sealed interface ApplyOutcome {
    val balance: Long

    data class Applied(
        override val balance: Long,
        val wasAlreadyApplied: Boolean,
    ) : ApplyOutcome

    data class InsufficientChips(
        override val balance: Long,
    ) : ApplyOutcome
}

@OptIn(ExperimentalTime::class)
interface WalletRepository {

    /**
     * Lazy-creates the wallet row with [Wallet.STARTER_GRANT] if missing,
     * then returns it. Idempotent — the second call for the same user is a
     * pure read.
     */
    suspend fun findOrCreate(userId: UserId): Wallet

    /**
     * Read-only lookup; returns null when no wallet row exists yet (the
     * user hasn't hit `GET /v1/me/wallet` since signup).
     */
    suspend fun find(userId: UserId): Wallet?

    /**
     * Apply an event idempotently. Constructs the wallet row if it's
     * missing (with the starter grant first, then applies the event).
     *
     * Implementations MUST take a transaction so the wallet balance and
     * the ledger row commit together or not at all. Re-applying the same
     * idempotency key returns [ApplyOutcome.Applied] with
     * `wasAlreadyApplied = true` and the current balance, without
     * mutating either table.
     */
    suspend fun apply(
        userId: UserId,
        idempotencyKey: String,
        delta: Long,
        reason: String,
    ): ApplyOutcome

    /** Recent ledger rows for a user, newest first, capped to [limit]. */
    suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent>

    /**
     * Wipe wallet + ledger for a user. Called from the `DELETE /v1/me`
     * flow so the account-delete cascade doesn't leave orphan chip rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}
