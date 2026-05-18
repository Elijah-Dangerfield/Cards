package com.dangerfield.cards.libraries.cards

/**
 * Reconciles local optimistic purchases with the server's authoritative
 * ledger.
 *
 * Lifecycle:
 *  - Fired by an app-launch hook (and/or foreground-resume) — see the impl
 *    module's [InventorySyncBootstrapper] for where it's installed.
 *  - One in-flight sync at a time. Concurrent callers share the same
 *    suspending result (single-flight).
 *
 * Outcomes from the server, per submitted purchase:
 *  - **Confirmed** → the row's [PurchaseState] flips from Pending to
 *    Confirmed. No chip movement.
 *  - **Reverted** → the row is deleted AND the server-supplied
 *    `chipsToRefund` is applied to the wallet via [ChipsRepository].
 *
 * Failure modes:
 *  - Network error: leaves entries as Pending. Next launch retries.
 *  - Server error / non-2xx: same as network error.
 *  - Empty inventory: no-op, returns success immediately without a call.
 *
 * Result-based — exceptions never escape the suspend boundary.
 */
interface InventorySyncService {
    suspend fun sync(): Result<Unit>
}
