package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Owns the optimistic local chip balance.
 *
 * Server-authoritative: the local store is a write-through cache. Reads
 * return the local balance for fast UI; writes ([addChips] / [subtractChips])
 * hit the local store first AND enqueue a `WalletEventEntity` for
 * [sync] to flush to the server's `wallets` ledger. The starter grant
 * itself lives in the server's `findOrCreate` transaction — the client
 * has no `STARTING_GRANT` constant. The first sync after install hydrates
 * the local balance from the server.
 *
 * **`null` means loading.** [observeBalance] and [getBalance] return null
 * until either (a) the first sync has hydrated the row, or (b) a local
 * optimistic write has seeded one. UI consumers should render a spinner
 * / hide the chip-count badge while null — that's the moment the server
 * grant is being established and any displayed value would be a guess.
 */
interface ChipsRepository {

    /**
     * Observable balance. `null` until the local store has a row — that's
     * either the first sync's authoritative hydrate or the first local
     * optimistic write, whichever happens first.
     */
    fun observeBalance(): Flow<Long?>

    /** One-shot read. `null` until the local store has a row (see [observeBalance]). */
    suspend fun getBalance(): Long?

    /**
     * Optimistic credit. Updates the singleton row by `+amount` AND
     * enqueues a `WalletEventEntity` keyed by [idempotencyKey] for the
     * sync service to flush. [amount] must be positive.
     *
     * If [idempotencyKey] is null the impl generates a UUID v4 — the
     * caller doesn't need a key unless they're trying to dedup across
     * retries themselves.
     */
    suspend fun addChips(amount: Long, reason: String = "client.unknown", idempotencyKey: String? = null)

    /**
     * Optimistic debit. Mirror of [addChips] with a negative delta.
     * [amount] must be positive — the impl signs it.
     *
     * The local store applies the debit unconditionally; the server-side
     * `WalletRepository.apply` is the one that may reject with
     * `InsufficientChips`, in which case the next sync resets the local
     * balance to the authoritative value (see [sync]).
     */
    suspend fun subtractChips(amount: Long, reason: String = "client.unknown", idempotencyKey: String? = null)

    /**
     * Overwrite the local balance with the server's authoritative value
     * without writing a ledger row. Called by [sync] after a successful
     * round-trip. If no local row exists yet, this is what populates it
     * for the first time. NOT for general code — the optimistic
     * [addChips] / [subtractChips] are the right thing 99% of the time.
     */
    suspend fun setBalance(authoritativeBalance: Long)

    /** Reset chips (used by "Fresh Start" / debug). */
    suspend fun deleteAll()

    /**
     * Reconcile optimistic local writes with the server's authoritative
     * `wallets` table. Single-flight (concurrent callers share one
     * in-flight call). Always issues the POST — an empty events list is a
     * valid balance-hydrate, which is how a second device picks up a chip
     * grant the user collected elsewhere.
     *
     * Triggered automatically on cold boot + warm foreground. Manual
     * callers (e.g. immediately after a shop redemption) can invoke
     * directly; the mutex collapses overlapping calls.
     *
     * Failure modes (network / 5xx / 401) leave pending events queued for
     * the next cycle. Result-based; exceptions never escape.
     */
    suspend fun sync(): Result<Unit>
}
