package com.dangerfield.cards.features.profile.impl.account

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityFeatureConfig
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import me.tatarka.inject.annotations.Inject

/**
 * Drives the "Claim your account" screen — wires the Supabase claim path
 * (`linkIdentity`) with explicit branching for "this OAuth is already on a
 * different account" (Supabase doesn't auto-merge; per the 2026-05-18
 * Identity pivot decision the user has to choose to lose guest progress
 * before switching).
 *
 * Provider buttons are gated by [IdentityFeatureConfig] flags so the UI
 * hides anything that isn't enabled in the Supabase dashboard yet —
 * nothing worse than a "Sign in with Apple" button that does nothing
 * because the provider hasn't been provisioned. Once the dashboard's
 * Providers tab gets credentials, flipping the AppConfig flag turns the
 * buttons on without a client release.
 *
 * The switch-to-existing-account path (signInWithOAuth instead of
 * linkIdentity) only kicks in if the claim returns
 * AlreadyOnAnotherAccount — a confirm dialog warns the user that their
 * guest chips and XP will be orphaned, and only then do we call sign-in.
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
            is ClaimAccountAction.DismissError -> action.updateState {
                it.copy(error = null, conflictingProvider = null)
            }

            is ClaimAccountAction.ClaimWith -> action.run {
                updateState { it.copy(isSubmitting = true, error = null, conflictingProvider = null) }
                when (val outcome = authRepository.linkOAuthIdentity(action.provider)) {
                    is LinkIdentityOutcome.Success -> {
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(ClaimAccountEvent.Claimed)
                    }
                    is LinkIdentityOutcome.AlreadyOnAnotherAccount -> updateState {
                        // Don't switch automatically — surface the choice.
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
                when (val outcome = authRepository.signInWithOAuth(provider)) {
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
}

val OAuthProvider.label: String
    get() = when (this) {
        OAuthProvider.Google -> "Google"
        OAuthProvider.Apple -> "Apple"
    }

data class ClaimAccountState(
    val googleEnabled: Boolean = false,
    val appleEnabled: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Set when the user attempted to link an identity already used by another account. */
    val conflictingProvider: OAuthProvider? = null,
) {
    val anyProviderEnabled: Boolean get() = googleEnabled || appleEnabled
}

sealed interface ClaimAccountEvent {
    /** Link succeeded — current identity now owns the new OAuth identity. */
    data object Claimed : ClaimAccountEvent
    /** User accepted switching to a pre-existing OAuth account. */
    data object SwitchedAccounts : ClaimAccountEvent
}

sealed interface ClaimAccountAction {
    data class ClaimWith(val provider: OAuthProvider) : ClaimAccountAction
    data object ConfirmSwitchToExisting : ClaimAccountAction
    data object DismissError : ClaimAccountAction
}
