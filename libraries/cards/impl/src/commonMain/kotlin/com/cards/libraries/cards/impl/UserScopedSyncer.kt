package com.dangerfield.cards.libraries.cards.impl

/**
 * A user-scoped store whose server state should be (re)reconciled whenever an
 * account is active — on becoming active (sign-in, cold-boot session resolve,
 * switch, guest claim), on a warm resume, and on connectivity returning.
 *
 * Implementers contribute themselves to the [UserScopedSyncer] multibinding and
 * declare nothing else; [UserScopedSyncCoordinator] owns the when (one level-based
 * `runWhen` loop per syncer, with retry). [sync] must be idempotent — the
 * coordinator retries failures and re-fires on every trigger edge.
 *
 * Not for stores with bespoke per-event work (e.g. clearing an in-memory cache on
 * a switch) — those stay direct [com.dangerfield.cards.libraries.cards.AppEventListener]s.
 */
interface UserScopedSyncer {
    suspend fun sync(): Result<Unit>
}
