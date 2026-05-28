package com.dangerfield.cards.features.profile.impl.account

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityFeatureConfig
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import me.tatarka.inject.annotations.Inject

/**
 * Drives the combined claim-account screen — email/password form at the
 * top, OAuth (Google/Apple) below an "or" divider. Same VM owns both:
 * one screen, one decision point, no second hop through SignUp.
 *
 * Provider buttons are gated by [IdentityFeatureConfig] flags so the UI
 * hides anything that isn't enabled in the Supabase dashboard yet —
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
    appConfigMap: AppConfigMap,
) : SEAViewModel<ClaimAccountState, ClaimAccountEvent, ClaimAccountAction>(
    initialStateArg = run {
        val config = IdentityFeatureConfig(appConfigMap)
        ClaimAccountState(
            googleEnabled = config.googleSignInEnabled,
            appleEnabled = config.appleSignInEnabled,
        )
    },
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
                it.copy(error = null, conflictingProvider = null)
            }

            is ClaimAccountAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run
                if (current.password != current.confirmPassword) {
                    updateState { it.copy(error = "Passwords don't match.") }
                    return@run
                }
                updateState { it.copy(awaitingClaimConfirm = true, error = null) }
            }

            is ClaimAccountAction.DismissClaimConfirm -> action.updateState {
                it.copy(awaitingClaimConfirm = false)
            }

            is ClaimAccountAction.ConfirmClaim -> action.run {
                val current = state
                if (!current.awaitingClaimConfirm) return@run
                updateState {
                    it.copy(awaitingClaimConfirm = false, isSubmitting = true, error = null)
                }
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
                            error = "That account is already in use. You can switch to it, " +
                                "but your current guest progress (chips, XP, achievements) won't carry over.",
                        )
                    }
                    is LinkIdentityOutcome.NotSignedIn -> updateState {
                        it.copy(isSubmitting = false, error = "Sign in first, then claim your account.")
                    }
                    is LinkIdentityOutcome.Cancelled -> updateState {
                        it.copy(isSubmitting = false)
                    }
                    is LinkIdentityOutcome.ProviderNotEnabled -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = "${action.provider.label} sign-in isn't available yet. Please try again later.",
                        )
                    }
                    is LinkIdentityOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
                    }
                    is LinkIdentityOutcome.Unknown -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't claim your account. Please try again.")
                    }
                }
            }

            is ClaimAccountAction.ConfirmSwitchToExisting -> action.run {
                val provider = state.conflictingProvider ?: return@run
                updateState { it.copy(isSubmitting = true, error = null, conflictingProvider = null) }
                when (authRepository.signInWithOAuth(provider)) {
                    is SignInOutcome.Success -> {
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(ClaimAccountEvent.SwitchedAccounts)
                    }
                    is SignInOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
                    }
                    is SignInOutcome.Cancelled -> updateState {
                        it.copy(isSubmitting = false)
                    }
                    is SignInOutcome.ProviderNotEnabled -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = "${provider.label} sign-in isn't available yet.",
                        )
                    }
                    is SignInOutcome.InvalidCredentials,
                    is SignInOutcome.EmailNotConfirmed,
                    is SignInOutcome.Unknown,
                        -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't sign in. Please try again.")
                    }
                }
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
                it.copy(isSubmitting = false, error = "That email is already in use. Try signing in instead.")
            }
            is LinkEmailIdentityOutcome.WeakPassword -> updateState {
                it.copy(isSubmitting = false, error = "Pick a stronger password (at least ${ClaimAccountState.MIN_PASSWORD_LENGTH} characters).")
            }
            is LinkEmailIdentityOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = "That email doesn't look right.")
            }
            LinkEmailIdentityOutcome.NotAnonymous,
            LinkEmailIdentityOutcome.NotSignedIn -> handleSignUpFallback(email, password)
            is LinkEmailIdentityOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
            }
            is LinkEmailIdentityOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = "Couldn't claim your account. Please try again.")
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
                it.copy(isSubmitting = false, error = "That email is already in use. Try signing in instead.")
            }
            is SignUpOutcome.WeakPassword -> updateState {
                it.copy(isSubmitting = false, error = "Pick a stronger password (at least ${ClaimAccountState.MIN_PASSWORD_LENGTH} characters).")
            }
            is SignUpOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = "That email doesn't look right.")
            }
            is SignUpOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
            }
            is SignUpOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = "Couldn't claim your account. Please try again.")
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
    val error: String? = null,
    /** Set when the user attempted to link an identity already used by another account. */
    val conflictingProvider: OAuthProvider? = null,
    /** True while the constructive claim-confirm dialog is up; the actual
     *  `linkEmailIdentity` call doesn't fire until the user confirms. */
    val awaitingClaimConfirm: Boolean = false,
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

sealed interface ClaimAccountAction {
    data class ClaimWith(val provider: OAuthProvider) : ClaimAccountAction
    data object ConfirmSwitchToExisting : ClaimAccountAction
    data class EmailChanged(val value: String) : ClaimAccountAction
    data class PasswordChanged(val value: String) : ClaimAccountAction
    data class ConfirmPasswordChanged(val value: String) : ClaimAccountAction
    data object Submit : ClaimAccountAction
    data object ConfirmClaim : ClaimAccountAction
    data object DismissClaimConfirm : ClaimAccountAction
    data object DismissError : ClaimAccountAction
}
