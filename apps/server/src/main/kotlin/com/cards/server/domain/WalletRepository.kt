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
         * Chip grant for crossing 100 finished multiplayer hands
         * (`HANDS_100_MP`). Replaces the borrowed single-player grinder
         * emote pack the achievement used to hand out — a chip grant is
         * the right reward for an MP volume milestone and needs no
         * dedicated cosmetic content. Applied idempotently per the
         * achievement's stable ledger key so re-evaluating the threshold
         * each finished hand never double-credits.
         */
        const val ACHIEVEMENT_HANDS_100_GRANT: Long = 2_500L

        /**
         * Chip grants for the per-hand-shape server-witnessed MP
         * achievements. Like [ACHIEVEMENT_HANDS_100_GRANT], these replace a
         * borrowed single-player cosmetic with a chip reward — an MP-only
         * achievement has no dedicated cosmetic content. Each is applied
         * once, idempotently, off the achievement's stable ledger key.
         */
        const val ACHIEVEMENT_FIRST_BUST_DEALT_MP_GRANT: Long = 1_000L
        const val ACHIEVEMENT_DOUBLE_UP_MP_GRANT: Long = 1_000L
        const val ACHIEVEMENT_TRIPLE_UP_MP_GRANT: Long = 2_000L
        const val ACHIEVEMENT_POT_5000_MP_GRANT: Long = 1_500L

        /**
         * Chip grants for the *cumulative* per-hand server-witnessed MP
         * achievements — earned once a career tally crosses the threshold.
         * Sized to the existing per-hand scale: EPIC `BUST_DEALT_5_MP` (five
         * scalps) matches [ACHIEVEMENT_TRIPLE_UP_MP_GRANT]; RARE
         * `WIN_BY_FOLD_10_MP` (ten pots without a showdown) matches the RARE
         * one-shots.
         */
        const val ACHIEVEMENT_BUST_DEALT_5_MP_GRANT: Long = 2_000L
        const val ACHIEVEMENT_WIN_BY_FOLD_10_MP_GRANT: Long = 1_000L

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

/**
 * Result of [WalletRepository.findOrCreateResult]. [created] is `true`
 * only on the call that actually inserted the wallet row (a brand-new
 * account whose starter grant was just seeded) — every later call for the
 * same user reads the existing row and returns `false`. The client uses
 * this to gate a once-per-account starter-grant reveal.
 */
data class FindOrCreateResult(val wallet: Wallet, val created: Boolean)

@OptIn(ExperimentalTime::class)
interface WalletRepository {

    /**
     * Lazy-creates the wallet row with [Wallet.STARTER_GRANT] if missing,
     * then returns it along with whether this call created it. Idempotent —
     * the second call for the same user is a pure read with
     * `created = false`.
     */
    suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult

    /**
     * Convenience wrapper around [findOrCreateResult] for callers that
     * don't care whether the row was just created.
     */
    suspend fun findOrCreate(userId: UserId): Wallet = findOrCreateResult(userId).wallet

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
     * True when the user has any real-money ledger event (`reason` prefixed
     * `iap.`). The orphan sweeps use this as the absolute deletion floor —
     * an account that ever paid is never sweep-deleted.
     */
    suspend fun hasIapSpend(userId: UserId): Boolean

    /**
     * Wipe wallet + ledger for a user. Called from the `DELETE /v1/me`
     * flow so the account-delete cascade doesn't leave orphan chip rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}
