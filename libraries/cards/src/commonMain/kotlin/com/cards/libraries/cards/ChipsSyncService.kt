package com.dangerfield.cards.libraries.cards

/**
 * Reconciles the optimistic local chip balance with the server-
 * authoritative `wallets` table.
 *
 * Lifecycle:
 *  - Fired by an app-launch hook (cold boot + foreground resume) — see the
 *    impl module's [ChipsSyncBootstrapper] for where it's installed.
 *  - One in-flight sync at a time. Concurrent callers share the same
 *    suspending result (single-flight).
 *
 * Sync model: the client maintains a list of locally-applied
 * `WalletEventEntity` rows (each carries an idempotency key). The
 * service POSTs them to `/v1/me/wallet/sync` and the server returns the
 * post-batch authoritative balance plus a per-event outcome:
 *
 *  - **Applied** / **AlreadyApplied** → the server's ledger has the
 *    event. Delete the local row; the local balance and the server
 *    balance match by construction.
 *  - **InsufficientChips** → the local optimistic write was rejected.
 *    The local row is deleted (no retry possible) and the local
 *    balance is reset to the server's authoritative value.
 *
 * After every successful sync we also overwrite the local balance with
 * the server's authoritative value, so a different device's writes
 * eventually converge here.
 *
 * Failure modes:
 *  - Network error / non-2xx: leaves pending rows in place. The next
 *    sync (cold boot / foreground / opportunistic) retries the same
 *    keys, which the server collapses idempotently.
 *  - Auth failure (401): same as network error — the next sync after
 *    the next refresh re-tries.
 *  - Empty pending set: still issues a sync to learn the server's
 *    authoritative balance, then updates local. The empty-batch hydrate
 *    is intentional — that's how a second device discovers a chip
 *    grant the user collected elsewhere.
 *
 * Result-based — exceptions never escape the suspend boundary.
 */
interface ChipsSyncService {
    suspend fun sync(): Result<Unit>
}
