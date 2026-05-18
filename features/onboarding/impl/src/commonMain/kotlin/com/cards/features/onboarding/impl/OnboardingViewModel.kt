package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import me.tatarka.inject.annotations.Inject

/**
 * Drives the onboarding pager. The interesting bit is [OnboardingAction.Finish] —
 * it has to do two things atomically from the user's perspective:
 *   1. Sign in anonymously via Supabase + bootstrap the profile row on
 *      our server (handled inside `IdentityRepository.ensureInitialized`).
 *   2. Mark `hasUserOnboarded = true` so we don't show the pager again on
 *      next launch.
 *
 * Order matters: identity first, onboarding flag second. If identity fails,
 * the flag stays false and the user can retry from the same screen — no
 * dead-end where they're "onboarded but un-identified."
 *
 * Likely failure modes: Supabase unreachable (no network, project paused),
 * our server unreachable (Fly cold start timeout, dev not running), or
 * JWT validation mismatch (server's `SUPABASE_JWT_SECRET` out of sync).
 * We surface a single generic message; debug builds capture the stack via
 * [logOnFailure].
 */
@Inject
class OnboardingViewModel(
    private val appCache: AppCache,
    private val identityRepository: IdentityRepository,
) : SEAViewModel<OnboardingState, OnboardingEvent, OnboardingAction>(
    initialStateArg = OnboardingState(),
) {

    override suspend fun handleAction(action: OnboardingAction) {
        when (action) {
            is OnboardingAction.Finish -> action.run {
                updateState { it.copy(isInitializing = true, error = null) }
                val result = Catching { identityRepository.ensureInitialized() }
                    .logOnFailure { "Failed to initialize identity on onboarding finish" }

                if (result.isSuccess) {
                    appCache.update { it.copy(hasUserOnboarded = true) }
                    updateState { it.copy(isInitializing = false) }
                    sendEvent(OnboardingEvent.NavigateToHome)
                } else {
                    updateState {
                        it.copy(
                            isInitializing = false,
                            error = "Couldn't reach the server. Check your connection and try again.",
                        )
                    }
                }
            }

            is OnboardingAction.DismissError -> action.updateState { it.copy(error = null) }
        }
    }
}

data class OnboardingState(
    val isInitializing: Boolean = false,
    val error: String? = null,
)

sealed interface OnboardingEvent {
    data object NavigateToHome : OnboardingEvent
}

sealed interface OnboardingAction {
    data object Finish : OnboardingAction
    data object DismissError : OnboardingAction
}
