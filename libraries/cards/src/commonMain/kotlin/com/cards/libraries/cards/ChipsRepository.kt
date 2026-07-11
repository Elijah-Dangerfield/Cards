package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Owns the optimistic local chip balance.
 *
 * Server-authoritative, optimistic display: the local store keeps the last
 * authoritative server **snapshot**, writes ([addChips] / [subtractChips])
 * enqueue a `WalletEventEntity` in the outbox for [sync] to flush, and the
 * balance the app shows is always **snapshot + pending outbox deltas**.
 * Deriving the display (instead of blending optimistic deltas into one
 * mutable number) is what makes the balance safe against every sync
 * ordering: a grant that lands while a sync is in flight stays a pending
 * row, so the authoritative overwrite can't hide it (PROG-11). The starter
 * grant itself lives in the server's `findOrCreate` transaction — the
 * client has no `STARTING_GRANT` constant; the first sync after install
 * hydrates the snapshot.
 *
 * **`null` means loading.** [observeBalance] and [getBalance] return null
 * until either (a) the first sync has hydrated the snapshot, or (b) a local
 * optimistic write has enqueued something. UI consumers should render a
 * spinner / hide the chip-count badge while null — that's the moment the
 * server grant is being established and any displayed value would be a guess.
 */
interface ChipsRepository {

    /**
     * Observable displayed balance — server snapshot plus pending outbox
     * deltas. `null` until the first sync hydrates the snapshot or a local
     * optimistic write enqueues something, whichever happens first.
     */
    fun observeBalance(): Flow<Long?>

    /** One-shot read of the same derived value (see [observeBalance]). */
    suspend fun getBalance(): Long?

    /**
     * Sync outcomes the user must hear about: the server refused one or
     * more pending events (`InsufficientChips` — a spend that no longer
     * fits the authoritative balance). The rows are dropped and the
     * displayed balance snaps to the server's truth, so without this
     * signal the correction would be silent. Collected once at the App
     * root and surfaced as an error snackbar.
     *
     * Defaults to an empty flow so fakes that don't drive sync needn't
     * implement it; the production impl overrides.
     */
    fun observeSyncRejections(): Flow<ChipSyncRejection> = emptyFlow()

    /**
     * Live, in-memory signal that a wallet was lazily *created* this session —
     * flips true on the [sync] whose response carries `walletCreated` (only the
     * first-contact response for a brand-new account; every later sync is false).
     *
     * Deliberately **not persisted**: it represents "a brand-new wallet just
     * appeared," which is server-authoritative and one-shot. The Home welcome
     * gate ANDs this with `!didSeeInitialGrantInOnboarding` to decide whether to
     * reveal the starter grant — so a pre-existing account (walletCreated=false)
     * never triggers the reveal, even right after switching into it.
     */
    val walletJustCreated: StateFlow<Boolean>

    /**
     * Live signal that a wallet reconcile ([sync]) is in flight — true from the
     * moment a sync starts until its round-trip resolves (success or failure).
     *
     * Wallet balances are server-authoritative, so there's a window after a game
     * / leave where the local balance is a *pre-settlement guess* the server
     * hasn't confirmed yet. Balance surfaces (Home, Shop) render the number as
     * "updating" while this is true, rather than showing a wrong-but-confident
     * value the user trusts. Distinct from [observeBalance] returning null (that's
     * "not hydrated at all"); this is "hydrated, but settling."
     *
     * Defaulted to a constant `false` flow so fakes that don't drive sync needn't
     * implement it; the production impl overrides.
     */
    val isReconciling: StateFlow<Boolean>
        get() = NeverReconciling

    /**
     * Optimistic credit. Enqueues a `WalletEventEntity` keyed by
     * [idempotencyKey] for the sync service to flush; the displayed balance
     * picks it up immediately via the pending fold. [amount] must be positive.
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
     * The pending debit is shown unconditionally; the server-side
     * `WalletRepository.apply` is the one that may reject with
     * `InsufficientChips`, in which case [sync] drops the row (the display
     * snaps back to authoritative) and announces it via
     * [observeSyncRejections].
     */
    suspend fun subtractChips(amount: Long, reason: String = "client.unknown", idempotencyKey: String? = null)

    /**
     * Overwrite the local server **snapshot** with an authoritative value,
     * without writing a ledger row. Called by [sync] after a successful
     * round-trip and by flows that receive a settled balance directly
     * (multiplayer leave, IAP redeem). Pending outbox events are unaffected —
     * they keep riding on top of the new snapshot until the server resolves
     * them. NOT for general code — the optimistic [addChips] /
     * [subtractChips] are the right thing 99% of the time.
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

private val NeverReconciling: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * One sync's worth of server-refused events (see
 * [ChipsRepository.observeSyncRejections]). [rejectedEvents] is how many
 * pending rows the server bounced; [rejectedChips] the total chips those
 * rows moved (absolute).
 */
data class ChipSyncRejection(val rejectedEvents: Int, val rejectedChips: Long)
