package com.dangerfield.cards.libraries.cards.impl

/**
 * A user-scoped store whose server state should be (re)reconciled whenever a
 * user becomes active — sign-in, cold-boot session resolve, or account switch —
 * a guest claims their account, or the app warm-resumes with a session live.
 *
 * Implementers contribute themselves to the [UserScopedSyncer] multibinding and
 * declare nothing else; [UserScopedSyncCoordinator] owns the when. This replaces
 * the identical onUserChanged/onAccountClaimed/onForeground triad these repos
 * used to each hand-roll as [com.dangerfield.cards.libraries.cards.AppEventListener]s.
 *
 * Not for stores with bespoke per-event work (e.g. clearing an in-memory cache on
 * a switch) — those stay direct listeners.
 */
interface UserScopedSyncer {
    suspend fun sync(): Result<Unit>
}
