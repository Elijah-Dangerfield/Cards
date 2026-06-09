package com.dangerfield.cards.libraries.identity.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Owns deferred guest-account creation. Onboarding kicks this off the moment
 * the user commits their name + avatar ([start]) and joins on the result at the
 * final step ([awaitTerminal]) — so the work runs in **app scope**, not the
 * onboarding ViewModel's scope, and survives the user paging through the
 * remaining onboarding steps.
 *
 * "Created" means an anonymous session now exists ([AuthRepository.createGuestSession]
 * succeeded); applying the chosen profile is best-effort on top (the server
 * already has a usable generated name either way). A failure to even establish
 * the session — i.e. offline — surfaces as [AccountCreationState.Failed], which
 * the degraded "we'll keep retrying" experience hangs off of and [retry] drives.
 */
interface GuestAccountCreator {

    /** Current creation state. Starts [AccountCreationState.Idle]. */
    val state: StateFlow<AccountCreationState>

    /**
     * Begin creating a guest account with the chosen [identity], in app scope.
     * Idempotent while already in progress or succeeded; a [AccountCreationState.Failed]
     * state can be restarted (callers usually go through [retry]).
     */
    fun start(identity: PendingIdentity)

    /** Suspends until creation reaches a terminal state (Succeeded / Failed). */
    suspend fun awaitTerminal(): AccountCreationState

    /**
     * Re-attempt a previously [AccountCreationState.Failed] creation with the
     * same held identity (e.g. on an offline→online flip, or a manual button).
     * No-op unless the current state is Failed.
     */
    fun retry()
}

/** The name + avatar the user chose in onboarding, carried until creation lands. */
data class PendingIdentity(
    val displayName: String?,
    val avatarEmoji: String?,
    val avatarBackgroundColor: String?,
)

/** Lifecycle of a deferred guest-account creation attempt. */
sealed interface AccountCreationState {
    /** Nothing kicked off yet. */
    data object Idle : AccountCreationState

    /** Creation is running (anon sign-in ± profile apply). */
    data object InProgress : AccountCreationState

    /** An anonymous session exists; the account is usable. */
    data object Succeeded : AccountCreationState

    /**
     * Couldn't establish a session (typically offline). [identity] is retained
     * so a retry can finish the job; [cause] is the underlying failure, if any.
     */
    data class Failed(
        val identity: PendingIdentity,
        val cause: Throwable?,
    ) : AccountCreationState
}
