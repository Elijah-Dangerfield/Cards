package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthOutcome
import com.dangerfield.cards.libraries.identity.auth.AuthOutcomeClassifier
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
    private val authOutcomeClassifier: AuthOutcomeClassifier,
    @Assisted private val email: String?,
    @Assisted private val guestLink: Boolean,
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
                        onNoSessionAfterCheck()
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
                    // A brand-new signup has no session until the confirmation link
                    // is tapped, so "no session" on a silent resume is just "not
                    // confirmed yet" — never yank the user out to "Welcome back"
                    // (AUTH-25). Only a guest whose real session genuinely died gets
                    // routed back to sign in.
                    is RefreshOutcome.SessionExpired ->
                        if (guestLink) sendEvent(VerifyEmailEvent.NavigateBackToSignIn)
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
     * The explicit "Check verification" tap came back with no session. For a
     * brand-new signup (or a returning user's unconfirmed-email sign-in) there
     * simply is no session until the confirmation link is tapped, so this means
     * "not confirmed yet" — surface the same nudge as [RefreshOutcome.StillPending]
     * instead of bouncing to "Welcome back" and clearing the back stack (AUTH-25).
     * A guest who linked an email did have a live session, so a genuine expiry
     * there still routes to sign in.
     */
    private suspend fun VerifyEmailAction.onNoSessionAfterCheck() {
        if (guestLink) {
            sendEvent(VerifyEmailEvent.NavigateBackToSignIn)
        } else {
            updateState { it.copy(banner = VerifyEmailState.Banner.StillPending) }
        }
    }

    /**
     * Where to go once the email is confirmed. Three outcomes:
     *  - an anonymous guest who linked an email identity keeps their existing
     *    account + progress, so they get the "account saved" confirmation (the
     *    email equivalent of the OAuth-link dialog) and land on Home;
     *  - a brand-new signup still needs to pick a name/avatar and see the
     *    starter grant, so it re-enters onboarding at the identity step;
     *  - a returning account (its email was merely unconfirmed) already has a
     *    profile + wallet, so it skips straight to Home.
     * The guest-link case is known statically from the route ([guestLink]);
     * new-vs-returning uses the same server signal the OAuth path does.
     */
    private suspend fun routeAfterConfirmation() {
        when (authOutcomeClassifier.classify(wasLink = guestLink)) {
            AuthOutcome.Linked -> {
                appCache.update { it.copy(hasUserOnboarded = true) }
                sendEvent(VerifyEmailEvent.NavigateToAccountSaved)
            }
            AuthOutcome.SignedUp -> sendEvent(VerifyEmailEvent.NavigateToOnboarding)
            AuthOutcome.SignedIn -> {
                appCache.update { it.copy(hasUserOnboarded = true) }
                sendEvent(VerifyEmailEvent.NavigateToHome)
            }
        }
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
    /** Anon guest's email link confirmed → show the "account saved" dialog, then Home. */
    data object NavigateToAccountSaved : VerifyEmailEvent
}

sealed interface VerifyEmailAction {
    data object IClickedTheLink : VerifyEmailAction
    data object Resend : VerifyEmailAction
    data object AppResumed : VerifyEmailAction
    data object ResolveEmailFromSession : VerifyEmailAction
}
