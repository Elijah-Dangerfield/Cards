package com.dangerfield.cards.features.profile.impl.account

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.auth.awaitCredential
import me.tatarka.inject.annotations.Inject

/**
 * Drives the combined claim-account screen — email/password form at the
 * top, OAuth (Google/Apple) below an "or" divider. Same VM owns both:
 * one screen, one decision point, no second hop through SignUp.
 *
 * Provider buttons are gated by the [GoogleSignInEnabled] / [AppleSignInEnabled]
 * config flags so the UI hides anything that isn't enabled in the Supabase dashboard yet —
 * nothing worse than a "Sign in with Apple" button that does nothing
 * because the provider hasn't been provisioned. Once the dashboard's
 * Providers tab gets credentials, flipping the AppConfig flag turns the
 * buttons on without a client release.
 *
 * Email path: validates locally (basic shape + ≥6 chars + match), then
 * shows a confirm dialog (the chips/XP claim is permanent and a typo'd
 * email is costly to undo), then calls `linkEmailIdentity` — falling
 * back to `signUpWithEmail` if the session has rolled over to a
 * non-anonymous state mid-flow.
 *
 * OAuth path: calls `linkOAuthIdentity` directly. The
 * `AlreadyOnAnotherAccount` outcome stashes the conflict and surfaces a
 * destructive-style confirm — only an explicit `ConfirmSwitchToExisting`
 * flips to `signInWithOAuth` (per the 2026-05-18 Identity pivot decision
 * that guest progress doesn't auto-merge).
 */
@Inject
class ClaimAccountViewModel(
    private val authRepository: AuthRepository,
    private val appleSignInCoordinator: AppleSignInCoordinator,
    googleSignInEnabled: GoogleSignInEnabled,
    appleSignInEnabled: AppleSignInEnabled,
) : SEAViewModel<ClaimAccountState, ClaimAccountEvent, ClaimAccountAction>(
    initialStateArg = ClaimAccountState(
        googleEnabled = googleSignInEnabled(),
        // Apple is the native flow only, which is iOS-only (see docs/decisions.md).
        appleEnabled = appleSignInEnabled() && BuildInfo.isiOS(),
    ),
) {

    override suspend fun handleAction(action: ClaimAccountAction) {
        when (action) {
            is ClaimAccountAction.EmailChanged -> action.updateState {
                it.copy(email = action.value, error = null)
            }
            is ClaimAccountAction.PasswordChanged -> action.updateState {
                it.copy(password = action.value, error = null)
            }
            is ClaimAccountAction.ConfirmPasswordChanged -> action.updateState {
                it.copy(confirmPassword = action.value, error = null)
            }

            is ClaimAccountAction.DismissError -> action.updateState {
                it.copy(error = null, conflictingProvider = null, pendingAppleCredential = null)
            }

            is ClaimAccountAction.Submit -> action.run {
                val current = state
                // `canSubmit` already requires the passwords to match, so there's
                // no separate mismatch branch here — the confirm field shows an
                // inline helper and the button stays disabled until they line up.
                if (!current.canSubmit) return@run
                updateState { it.copy(isSubmitting = true, error = null) }
                handleLinkEmail(current.email.trim(), current.password)
            }

            is ClaimAccountAction.ClaimWith -> action.run {
                updateState { it.copy(isSubmitting = true, error = null, conflictingProvider = null) }
                when (authRepository.linkOAuthIdentity(action.provider)) {
                    is LinkIdentityOutcome.Success -> {
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(ClaimAccountEvent.Claimed)
                    }
                    is LinkIdentityOutcome.AlreadyOnAnotherAccount -> updateState {
                        it.copy(
                            isSubmitting = false,
                            conflictingProvider = action.provider,
                            error = ClaimAccountError.AlreadyOnAnotherAccount,
                        )
                    }
                    is LinkIdentityOutcome.NotSignedIn -> updateState {
                        it.copy(isSubmitting = false, error = ClaimAccountError.NotSignedIn)
                    }
                    is LinkIdentityOutcome.Cancelled -> updateState {
                        it.copy(isSubmitting = false)
                    }
                    is LinkIdentityOutcome.ProviderNotEnabled -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = ClaimAccountError.ProviderNotEnabled(action.provider),
                        )
                    }
                    is LinkIdentityOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = ClaimAccountError.NetworkError)
                    }
                    is LinkIdentityOutcome.Unknown -> updateState {
                        it.copy(isSubmitting = false, error = ClaimAccountError.Unknown)
                    }
                }
            }

            is ClaimAccountAction.ClaimWithApple -> action.run {
                updateState { it.copy(isSubmitting = true, error = null, conflictingProvider = null) }
                Catching { appleSignInCoordinator.awaitCredential() }
                    .logOnFailure { "Apple credential request failed (claim)" }
                    .fold(
                        onSuccess = { credential ->
                            if (credential == null) {
                                // Dismissed the sheet — quiet no-op.
                                updateState { it.copy(isSubmitting = false) }
                            } else {
                                linkApple(credential)
                            }
                        },
                        onFailure = {
                            updateState {
                                it.copy(isSubmitting = false, error = ClaimAccountError.Unknown)
                            }
                        },
                    )
            }

            is ClaimAccountAction.ConfirmSwitchToExisting -> action.run {
                val provider = state.conflictingProvider ?: return@run
                val appleCredential = state.pendingAppleCredential
                updateState {
                    it.copy(
                        isSubmitting = true,
                        error = null,
                        conflictingProvider = null,
                        pendingAppleCredential = null,
                    )
                }
                // Reuse the Apple credential we already captured so the user
                // doesn't have to re-run the sheet; Google still uses the web flow.
                val outcome = if (provider == OAuthProvider.Apple && appleCredential != null) {
                    authRepository.signInWithApple(appleCredential)
                } else {
                    authRepository.signInWithOAuth(provider)
                }
                when (outcome) {
                    is SignInOutcome.Success -> {
                        // Switched to a pre-existing account. No grant-flag
                        // bookkeeping: the Home dialog keys on the live
                        // walletJustCreated signal (false for a pre-existing
                        // account), so it can't fire here.
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(ClaimAccountEvent.SwitchedAccounts)
                    }
                    is SignInOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = ClaimAccountError.NetworkError)
                    }
                    is SignInOutcome.Cancelled -> updateState {
                        it.copy(isSubmitting = false)
                    }
                    is SignInOutcome.ProviderNotEnabled -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = ClaimAccountError.ProviderNotEnabled(provider),
                        )
                    }
                    is SignInOutcome.InvalidCredentials,
                    is SignInOutcome.EmailNotConfirmed,
                    is SignInOutcome.Unknown,
                        -> updateState {
                        it.copy(isSubmitting = false, error = ClaimAccountError.SwitchFailed)
                    }
                }
            }
        }
    }

    /**
     * Link the captured Apple identity to the current (anonymous) account.
     * Mirrors the [ClaimAccountAction.ClaimWith] OAuth path, but on an
     * already-on-another-account conflict it stashes the [credential] so
     * [ClaimAccountAction.ConfirmSwitchToExisting] can switch without re-running
     * the Apple sheet.
     */
    private suspend fun ClaimAccountAction.linkApple(credential: AppleSignInCredential) {
        when (authRepository.linkAppleIdentity(credential)) {
            is LinkIdentityOutcome.Success -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(ClaimAccountEvent.Claimed)
            }
            is LinkIdentityOutcome.AlreadyOnAnotherAccount -> updateState {
                it.copy(
                    isSubmitting = false,
                    conflictingProvider = OAuthProvider.Apple,
                    pendingAppleCredential = credential,
                    error = ClaimAccountError.AlreadyOnAnotherAccount,
                )
            }
            is LinkIdentityOutcome.NotSignedIn -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.NotSignedIn)
            }
            is LinkIdentityOutcome.Cancelled -> updateState { it.copy(isSubmitting = false) }
            is LinkIdentityOutcome.ProviderNotEnabled -> updateState {
                it.copy(
                    isSubmitting = false,
                    error = ClaimAccountError.ProviderNotEnabled(OAuthProvider.Apple),
                )
            }
            is LinkIdentityOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.NetworkError)
            }
            is LinkIdentityOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.Unknown)
            }
        }
    }

    private suspend fun ClaimAccountAction.handleLinkEmail(email: String, password: String) {
        when (val outcome = authRepository.linkEmailIdentity(email, password)) {
            is LinkEmailIdentityOutcome.VerificationRequired -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(ClaimAccountEvent.NavigateToVerifyEmail(outcome.email))
            }
            is LinkEmailIdentityOutcome.EmailAlreadyRegistered -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.EmailAlreadyRegistered)
            }
            is LinkEmailIdentityOutcome.WeakPassword -> updateState {
                it.copy(
                    isSubmitting = false,
                    error = ClaimAccountError.WeakPassword(ClaimAccountState.MIN_PASSWORD_LENGTH),
                )
            }
            is LinkEmailIdentityOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.InvalidEmail)
            }
            LinkEmailIdentityOutcome.NotAnonymous,
            LinkEmailIdentityOutcome.NotSignedIn -> handleSignUpFallback(email, password)
            is LinkEmailIdentityOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.NetworkError)
            }
            is LinkEmailIdentityOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.Unknown)
            }
        }
    }

    private suspend fun ClaimAccountAction.handleSignUpFallback(email: String, password: String) {
        when (val outcome = authRepository.signUpWithEmail(email = email, password = password)) {
            is SignUpOutcome.VerificationRequired -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(ClaimAccountEvent.NavigateToVerifyEmail(outcome.email))
            }
            is SignUpOutcome.EmailAlreadyRegistered -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.EmailAlreadyRegistered)
            }
            is SignUpOutcome.WeakPassword -> updateState {
                it.copy(
                    isSubmitting = false,
                    error = ClaimAccountError.WeakPassword(ClaimAccountState.MIN_PASSWORD_LENGTH),
                )
            }
            is SignUpOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.InvalidEmail)
            }
            is SignUpOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.NetworkError)
            }
            is SignUpOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = ClaimAccountError.Unknown)
            }
        }
    }
}

val OAuthProvider.label: String
    get() = when (this) {
        OAuthProvider.Google -> "Google"
        OAuthProvider.Apple -> "Apple"
    }

data class ClaimAccountState(
    val googleEnabled: Boolean = false,
    val appleEnabled: Boolean = false,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: ClaimAccountError? = null,
    /** Set when the user attempted to link an identity already used by another account. */
    val conflictingProvider: OAuthProvider? = null,
    /**
     * The Apple credential captured during a claim attempt, retained across an
     * `AlreadyOnAnotherAccount` conflict so "switch to existing" can sign in
     * without re-running the native Apple sheet. Not held for the web (Google) flow.
     */
    val pendingAppleCredential: AppleSignInCredential? = null,
) {
    val anyProviderEnabled: Boolean get() = googleEnabled || appleEnabled

    val passwordMismatch: Boolean
        get() = confirmPassword.isNotEmpty() && password != confirmPassword

    val canSubmit: Boolean
        get() = !isSubmitting &&
            email.contains('@') &&
            password.length >= MIN_PASSWORD_LENGTH &&
            confirmPassword == password

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

sealed interface ClaimAccountEvent {
    /** Link succeeded — current identity now owns the new OAuth identity. */
    data object Claimed : ClaimAccountEvent
    /** User accepted switching to a pre-existing OAuth account. */
    data object SwitchedAccounts : ClaimAccountEvent
    /** Email link kicked off — guest stays signed in until they tap the link. */
    data class NavigateToVerifyEmail(val email: String) : ClaimAccountEvent
}

/**
 * Inline error surfaced under the claim form. Typed so the VM doesn't
 * hold raw user-facing copy — `ClaimAccountScreen.kt` resolves each
 * variant through Compose Multiplatform resources at render time.
 * `ProviderNotEnabled` and `WeakPassword` carry their format args so the
 * resource format params stay typed end-to-end.
 */
sealed interface ClaimAccountError {
    /** OAuth link rejected because the provider is already on another account. */
    data object AlreadyOnAnotherAccount : ClaimAccountError
    /** OAuth link requires a signed-in session. */
    data object NotSignedIn : ClaimAccountError
    /** OAuth provider isn't enabled in Supabase dashboard yet. */
    data class ProviderNotEnabled(val provider: OAuthProvider) : ClaimAccountError
    /** Network unreachable (any path). */
    data object NetworkError : ClaimAccountError
    /** Generic claim-failure (OAuth link or email link). */
    data object Unknown : ClaimAccountError
    /** Switch-to-existing OAuth account failed (non-network, non-cancel branches). */
    data object SwitchFailed : ClaimAccountError
    /** Email-claim path: email already in use. */
    data object EmailAlreadyRegistered : ClaimAccountError
    /** Email-claim path: server rejected the password as too weak. */
    data class WeakPassword(val minLength: Int) : ClaimAccountError
    /** Email-claim path: server rejected the email shape. */
    data object InvalidEmail : ClaimAccountError
}

sealed interface ClaimAccountAction {
    data class ClaimWith(val provider: OAuthProvider) : ClaimAccountAction
    /** Native "Sign in with Apple" claim (iOS) — runs the coordinator, then links. */
    data object ClaimWithApple : ClaimAccountAction
    data object ConfirmSwitchToExisting : ClaimAccountAction
    data class EmailChanged(val value: String) : ClaimAccountAction
    data class PasswordChanged(val value: String) : ClaimAccountAction
    data class ConfirmPasswordChanged(val value: String) : ClaimAccountAction
    data object Submit : ClaimAccountAction
    data object DismissError : ClaimAccountAction
}
