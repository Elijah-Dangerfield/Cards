package com.dangerfield.cards.features.onboarding.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import kotlinx.coroutines.launch
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
 * Likely failure modes (and how they surface):
 *  - **Anonymous sign-ins disabled in the Supabase dashboard** — by far the
 *    most common dev/staging issue. We sniff the error message for
 *    "anonymous" + "disabled" / "not enabled" and surface a specific
 *    actionable message in debug builds so the dev knows where to look.
 *  - Supabase unreachable (no network, project paused).
 *  - Our server unreachable (Fly cold start timeout, dev not running, JWT
 *    secret out of sync).
 *
 * In debug builds the error message also includes the underlying exception
 * detail (truncated) — so a tester can read it directly without diving into
 * logs. Release builds get a friendly generic message + a "Report a bug"
 * affordance.
 *
 * Hard guard on init: if `AppData.hasUserOnboarded` is already true, fire
 * [OnboardingEvent.NavigateToHome] immediately. This is a safety net against
 * any path that lands a returning user back on the onboarding pager — e.g.
 * a root recomposition resetting the nav graph, an accidental nav from a
 * future surface, or a deep link. The pager itself only flips the flag on
 * success, so this guard is one-way: a brand-new user (`false`) never trips
 * it, and a sign-out (which flips the flag back to `false` first, then
 * navigates) also doesn't trip it.
 */
@Inject
class OnboardingViewModel(
    private val appCache: AppCache,
    private val identityRepository: IdentityRepository,
) : SEAViewModel<OnboardingState, OnboardingEvent, OnboardingAction>(
    initialStateArg = OnboardingState(),
) {

    init {
        viewModelScope.launch {
            Catching {
                if (appCache.get().hasUserOnboarded) {
                    sendEvent(OnboardingEvent.NavigateToHome)
                }
            }.logOnFailure { "Onboarded-guard cache read failed" }
        }
    }

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
                            error = describeFailure(result.exceptionOrNull()),
                        )
                    }
                }
            }

            is OnboardingAction.DismissError -> action.updateState { it.copy(error = null) }
        }
    }

    /**
     * Map the underlying exception onto something a user (or a dev
     * looking at the screen) can act on. Pattern-matches against the most
     * common Supabase responses; falls back to a generic line for the
     * rest. Debug builds get the exception message tacked on so dev can
     * see what's going on without opening the logs.
     */
    private fun describeFailure(cause: Throwable?): String {
        val msg = cause?.message.orEmpty().lowercase()
        val friendly = when {
            "anonymous" in msg && ("disabled" in msg || "not enabled" in msg || "not allowed" in msg) ->
                "Anonymous sign-in isn't enabled in this Supabase project. " +
                    "Enable it in Authentication → Providers → Email (Allow anonymous sign-ins)."
            "captcha" in msg ->
                "Captcha is required by this Supabase project. Disable it in dev or wire a token."
            // Supabase returns 401 with "JWT" when the anon key is wrong / expired.
            "jwt" in msg || "invalid api key" in msg ->
                "The Supabase anon key looks wrong or expired. Check IdentityConfig."
            "unable to resolve host" in msg ||
                "failed to connect" in msg ||
                "network is unreachable" in msg ||
                "timeout" in msg ->
                "Couldn't reach the server. Check your connection and try again."
            else -> "Couldn't reach the server. Check your connection and try again."
        }
        // In debug builds, surface the raw exception so the dev sees the
        // wire-level reason without going to logcat. Truncate to keep the
        // toast readable.
        return if (BuildInfo.isDebug && cause != null) {
            val raw = (cause.message ?: cause::class.simpleName.orEmpty())
                .take(200)
            "$friendly\n\nDEBUG: $raw"
        } else {
            friendly
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
