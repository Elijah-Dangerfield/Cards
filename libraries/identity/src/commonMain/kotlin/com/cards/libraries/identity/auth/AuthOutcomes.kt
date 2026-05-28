package com.dangerfield.cards.libraries.identity.auth

/**
 * Outcomes for [AuthRepository] operations. Sealed so the UI can render
 * specific messages without try/catch ceremony.
 *
 * Note: `Success` variants carry no profile payload — the new auth
 * state will be visible on [AuthRepository.observe], and the matching
 * profile follows automatically once `ProfileRepository` observes the
 * auth change.
 */

enum class OAuthProvider { Google, Apple }

sealed interface SignInOutcome {
    data object Success : SignInOutcome
    data object InvalidCredentials : SignInOutcome
    data class EmailNotConfirmed(val email: String) : SignInOutcome
    data object Cancelled : SignInOutcome
    data object ProviderNotEnabled : SignInOutcome
    data class NetworkError(val cause: Throwable) : SignInOutcome
    data class Unknown(val cause: Throwable) : SignInOutcome
}

sealed interface SignUpOutcome {
    data class VerificationRequired(val email: String) : SignUpOutcome
    data object EmailAlreadyRegistered : SignUpOutcome
    data object WeakPassword : SignUpOutcome
    data object InvalidEmail : SignUpOutcome
    data class NetworkError(val cause: Throwable) : SignUpOutcome
    data class Unknown(val cause: Throwable) : SignUpOutcome
}

sealed interface RefreshOutcome {
    /** Session refreshed and email is confirmed. UI may advance to home. */
    data object EmailConfirmed : RefreshOutcome
    /** Session refreshed but email still pending. UI stays on verify screen. */
    data object StillPending : RefreshOutcome
    /** Session was invalid / expired / revoked; UI should reset to sign-in. */
    data object SessionExpired : RefreshOutcome
    data class NetworkError(val cause: Throwable) : RefreshOutcome
    data class Unknown(val cause: Throwable) : RefreshOutcome
}

sealed interface ResendOutcome {
    data object Sent : ResendOutcome
    data class RateLimited(val retryAfterSeconds: Int?) : ResendOutcome
    data class NetworkError(val cause: Throwable) : ResendOutcome
    data class Unknown(val cause: Throwable) : ResendOutcome
}

sealed interface SendResetOutcome {
    data object Sent : SendResetOutcome
    data object RateLimited : SendResetOutcome
    data class NetworkError(val cause: Throwable) : SendResetOutcome
    data class Unknown(val cause: Throwable) : SendResetOutcome
}

sealed interface DeleteAccountOutcome {
    data object Success : DeleteAccountOutcome
    data object NotConfigured : DeleteAccountOutcome
    data object NotSignedIn : DeleteAccountOutcome
    /**
     * Anonymous accounts can't be deleted — there's nothing to delete that
     * a sign-out wouldn't already cover. Both the client and the server
     * reject the request; the client check is the fast path, the server
     * check is the authoritative one (JWT carries the `is_anonymous`
     * claim).
     */
    data object AnonymousNotAllowed : DeleteAccountOutcome
    data class NetworkError(val cause: Throwable) : DeleteAccountOutcome
    data class Unknown(val cause: Throwable) : DeleteAccountOutcome
}

sealed interface LinkIdentityOutcome {
    data object Success : LinkIdentityOutcome
    data object AlreadyOnAnotherAccount : LinkIdentityOutcome
    data object NotSignedIn : LinkIdentityOutcome
    data object Cancelled : LinkIdentityOutcome
    data object ProviderNotEnabled : LinkIdentityOutcome
    data class NetworkError(val cause: Throwable) : LinkIdentityOutcome
    data class Unknown(val cause: Throwable) : LinkIdentityOutcome
}

sealed interface LinkEmailIdentityOutcome {
    data class VerificationRequired(val email: String) : LinkEmailIdentityOutcome
    data object EmailAlreadyRegistered : LinkEmailIdentityOutcome
    data object WeakPassword : LinkEmailIdentityOutcome
    data object InvalidEmail : LinkEmailIdentityOutcome
    data object NotAnonymous : LinkEmailIdentityOutcome
    data object NotSignedIn : LinkEmailIdentityOutcome
    data class NetworkError(val cause: Throwable) : LinkEmailIdentityOutcome
    data class Unknown(val cause: Throwable) : LinkEmailIdentityOutcome
}
