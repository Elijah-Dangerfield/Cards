package com.dangerfield.cards.libraries.core

/**
 * What an action needs of the user's identity before it can proceed. Routes
 * declare it per-destination, authed network calls per-call; both are enforced
 * centrally through [AuthGate], so a feature opts in with one argument — no
 * per-screen or per-call guard code.
 */
enum class AuthRequirement {
    /** Anyone — the default. */
    None,

    /** A real (server) session of any kind, including a guest. Blocks the
     *  unauthenticated state (signed out, or a guest whose creation is still
     *  pending). Use for multiplayer, friends rooms, anything that hits the
     *  user's server-side account. */
    Account,

    /** A *claimed* (non-anonymous) account. Blocks guests too. Use for things a
     *  throwaway guest shouldn't do — e.g. real-money purchases. */
    ClaimedAccount,
}

/**
 * Why auth-readiness is blocked. The one vocabulary shared by the nav gate's
 * sheet copy, typed call failures ([AuthUnready]), and anything else that has
 * to explain "why can't this user do this" honestly.
 */
enum class AuthReason {
    /** Guest-account creation is still finishing (or healing). Self-resolves;
     *  ask the user to wait a moment. */
    FinishingSetup,

    /** No account at all — offer to create one / sign in. */
    NeedAccount,

    /** Has a guest account but the action needs a claimed one — offer to save it. */
    NeedClaimedAccount,

    /**
     * The session couldn't be confirmed because the device is offline, not
     * because the user has no account. Tell them they're offline instead of the
     * misleading "account needed" — an onboarded guest reads that as "my
     * progress is gone."
     */
    Offline,

    /** The auth server rejected the session; the user must sign in again. */
    SessionExpired,
}

/** The answer to "may this proceed?" for a given [AuthRequirement]. */
sealed interface AuthVerdict {
    data object Ready : AuthVerdict
    data class Blocked(val reason: AuthReason) : AuthVerdict
}

/**
 * The single authority on auth-readiness. One implementation (contributed from
 * identity) caches auth, guest-creation, and connectivity state; the navigation
 * gate and the authed call boundary both consult it, so they can never disagree.
 *
 * [verdict] is a synchronous peek over cached state — safe on the router's
 * non-suspending navigate path — and fails closed while auth is still
 * resolving. [awaitVerdict] first suspends until auth has resolved; use it
 * anywhere that can run at launch (background syncs), where fail-closed would
 * wrongly block.
 */
interface AuthGate {
    fun verdict(requirement: AuthRequirement): AuthVerdict
    suspend fun awaitVerdict(requirement: AuthRequirement): AuthVerdict
}

/**
 * Typed failure for an authed call that was short-circuited before firing (or
 * discovered mid-flight to be session-dead). Surface it with [mapAuthFailure] /
 * [onAuthFailure]; `getOrNull()` already ignores it like any other failure.
 */
class AuthUnready(val reason: AuthReason, cause: Throwable? = null) :
    Exception("auth unready: $reason", cause)

/** Maps an [AuthUnready] failure to a success value; real failures (timeouts, 5xx) pass through. */
inline fun <T> Catching<T>.mapAuthFailure(map: (AuthReason) -> T): Catching<T> =
    when (val exception = exceptionOrNull()) {
        is AuthUnready -> Catching.success(map(exception.reason))
        else -> this
    }

/** Side-effect on an [AuthUnready] failure (e.g. emit a nav event); the [Catching] passes through unchanged. */
inline fun <T> Catching<T>.onAuthFailure(action: (AuthReason) -> Unit): Catching<T> =
    onFailure { if (it is AuthUnready) action(it.reason) }
