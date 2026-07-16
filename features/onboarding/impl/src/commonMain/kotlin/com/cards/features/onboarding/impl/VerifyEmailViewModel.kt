package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * "Check your email" screen logic. The user has just signed up; Supabase
 * sent them a verification link. We sit on this screen until they
 * confirm.
 *
 * Three triggers refresh the session:
 *  - **I clicked the link** → user-initiated. On `StillPending` we surface
 *    a banner so the user knows we tried and they should re-check the
 *    inbox.
 *  - **Resend** → re-send the verification email; surface rate-limit
 *    errors so the user knows to wait.
 *  - **Foreground resume** → silently retry whenever the screen is
 *    re-resumed. Fires on every return-to-app, including the
 *    same-device `cards://auth/confirmed` deep-link bounce after the
 *    user taps the verification link in their browser. Silent because a
 *    user who switched apps for unrelated reasons shouldn't see a banner
 *    flash on return; the manual button is the explicit surface for
 *    diagnostic feedback.
 *
 * No auto-polling beyond resume — battery + design choice.
 *
 * The constructor [email] arg is nullable to support the cold-launch
 * deep-link path (`cards://auth/confirmed`) where the URL doesn't carry
 * the address. When null, the VM resolves it from the active Supabase
 * session via [VerifyEmailAction.ResolveEmailFromSession]; until that
 * lands, the screen reads from [VerifyEmailState.email] which is the
 * provided value or empty string.
 */
@Inject
class VerifyEmailViewModel(
    private val authRepository: AuthRepository,
    private val appCache: AppCache,
    private val chipsRepository: ChipsRepository,
    @Assisted private val email: String?,
) : SEAViewModel<VerifyEmailState, VerifyEmailEvent, VerifyEmailAction>(
    initialStateArg = VerifyEmailState(email = email.orEmpty()),
) {

    init {
        if (email.isNullOrEmpty()) {
            takeAction(VerifyEmailAction.ResolveEmailFromSession)
        }
    }

    override suspend fun handleAction(action: VerifyEmailAction) {
        when (action) {
            is VerifyEmailAction.ResolveEmailFromSession -> action.run {
                val authState = authRepository.current()
                val resolved = (authState as? AuthState.Authenticated)?.email
                if (!resolved.isNullOrEmpty()) {
                    updateState { it.copy(email = resolved) }
                }
            }

            is VerifyEmailAction.IClickedTheLink -> action.run {
                updateState { it.copy(isRefreshing = true, banner = null) }

                when (authRepository.refreshSession()) {
                    is RefreshOutcome.EmailConfirmed -> {
                        updateState { it.copy(isRefreshing = false) }
                        routeAfterConfirmation()
                    }
                    is RefreshOutcome.StillPending -> updateState {
                        it.copy(
                            isRefreshing = false,
                            banner = VerifyEmailState.Banner.StillPending,
                        )
                    }
                    is RefreshOutcome.SessionExpired -> {
                        updateState { it.copy(isRefreshing = false) }
                        sendEvent(VerifyEmailEvent.NavigateBackToSignIn)
                    }
                    is RefreshOutcome.NetworkError -> updateState {
                        it.copy(
                            isRefreshing = false,
                            banner = VerifyEmailState.Banner.NetworkError,
                        )
                    }
                    is RefreshOutcome.Unknown -> updateState {
                        it.copy(
                            isRefreshing = false,
                            banner = VerifyEmailState.Banner.GenericError,
                        )
                    }
                }
            }

            is VerifyEmailAction.AppResumed -> action.run {
                when (authRepository.refreshSession()) {
                    is RefreshOutcome.EmailConfirmed -> routeAfterConfirmation()
                    is RefreshOutcome.SessionExpired ->
                        sendEvent(VerifyEmailEvent.NavigateBackToSignIn)
                    is RefreshOutcome.StillPending,
                    is RefreshOutcome.NetworkError,
                    is RefreshOutcome.Unknown,
                    -> Unit
                }
            }

            is VerifyEmailAction.Resend -> action.run {
                val target = state.email
                if (target.isEmpty()) return@run
                updateState { it.copy(isResending = true, banner = null) }
                val outcome = authRepository.resendVerificationEmail(target)
                val banner = when (outcome) {
                    is ResendOutcome.Sent -> VerifyEmailState.Banner.ResendSent
                    is ResendOutcome.RateLimited -> VerifyEmailState.Banner.ResendRateLimited
                    is ResendOutcome.NetworkError -> VerifyEmailState.Banner.NetworkError
                    is ResendOutcome.Unknown -> VerifyEmailState.Banner.GenericError
                }
                updateState { it.copy(isResending = false, banner = banner) }
            }
        }
    }

    /**
     * Where to go once the email is confirmed. A brand-new signup still needs to
     * pick a name/avatar and see the starter grant, so it re-enters onboarding at
     * the identity step; a returning account (its email was merely unconfirmed)
     * already has a profile + wallet, so it skips straight to Home. Same
     * new-vs-returning discriminator the OAuth path uses.
     */
    private suspend fun routeAfterConfirmation() {
        if (isBrandNewAccount()) {
            sendEvent(VerifyEmailEvent.NavigateToOnboarding)
        } else {
            appCache.update { it.copy(hasUserOnboarded = true) }
            sendEvent(VerifyEmailEvent.NavigateToHome)
        }
    }

    /**
     * Whether the just-confirmed account is brand new. Mirrors the onboarding
     * OAuth path: [ChipsRepository.walletJustCreated] flips true the first time a
     * fresh account's wallet is synced, and stays false for a returning account
     * whose wallet already exists. If the sync fails (offline) the signal stays
     * false and we treat them as returning (Home) rather than trapping a real
     * user in onboarding.
     */
    private suspend fun isBrandNewAccount(): Boolean {
        Catching { chipsRepository.sync() }.logOnFailure { "Verify-email wallet sync failed" }
        return chipsRepository.walletJustCreated.value
    }
}

data class VerifyEmailState(
    val email: String,
    val isRefreshing: Boolean = false,
    val isResending: Boolean = false,
    val banner: Banner? = null,
) {
    enum class Banner {
        StillPending,
        ResendSent,
        ResendRateLimited,
        NetworkError,
        GenericError,
    }
}

sealed interface VerifyEmailEvent {
    data object NavigateToHome : VerifyEmailEvent
    data object NavigateBackToSignIn : VerifyEmailEvent
    /** Brand-new signup confirmed → re-enter onboarding at the identity step. */
    data object NavigateToOnboarding : VerifyEmailEvent
}

sealed interface VerifyEmailAction {
    data object IClickedTheLink : VerifyEmailAction
    data object Resend : VerifyEmailAction
    data object AppResumed : VerifyEmailAction
    data object ResolveEmailFromSession : VerifyEmailAction
}
