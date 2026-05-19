package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Owns the optimistic local chip balance.
 *
 * Server-authoritative as of V6: the local store is a write-through cache.
 * Reads return the local balance for fast UI; writes ([applyDelta]) hit
 * the local store first AND enqueue a `WalletEventEntity` for
 * [ChipsSyncService] to flush to the server's `wallets` ledger.
 *
 * The first cold-boot sync hydrates the local balance from the server,
 * so a reinstall on the same anonymous-user identity comes back to the
 * same wallet — no double-starter exploit.
 */
interface ChipsRepository {

    /** Observable balance. Lazy-seeds on first read if the row doesn't
     *  exist — the seeded value is overwritten by the next sync's
     *  authoritative answer. */
    fun observeBalance(): Flow<Long>

    /** One-shot read. Lazy-seeds on first read if the row doesn't exist. */
    suspend fun getBalance(): Long

    /**
     * Optimistic local write. Updates the singleton row by `delta` AND
     * enqueues a `WalletEventEntity` keyed by [idempotencyKey] for the
     * sync service to flush. Reasons travel with the event so the
     * server-side ledger has a why-trail.
     *
     * If [idempotencyKey] is null the impl generates a UUID v4 — the
     * caller doesn't need a key unless they're trying to dedup across
     * retries themselves.
     */
    suspend fun applyDelta(delta: Long, reason: String = "client.unknown", idempotencyKey: String? = null)

    /**
     * Overwrite the local balance with the server's authoritative value
     * without writing a ledger row. Called by [ChipsSyncService] after
     * a successful round-trip. NOT for general code — the optimistic
     * [applyDelta] is the right thing 99% of the time.
     */
    suspend fun setBalance(authoritativeBalance: Long)

    /** Reset chips (used by "Fresh Start" / debug). */
    suspend fun deleteAll()

    companion object {
        /** Initial grant seeded on first launch. Mirrors the server's
         *  `Wallet.STARTER_GRANT`; the server's value is authoritative once
         *  the first sync lands. */
        const val STARTING_GRANT: Long = 10_000L
    }
}
