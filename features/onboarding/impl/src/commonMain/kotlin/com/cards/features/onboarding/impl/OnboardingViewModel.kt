package com.dangerfield.cards.features.onboarding.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityFeatureConfig
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the three-step onboarding flow:
 *   1. **Welcome** — "Continue as guest" anon-signs-in then advances to step 2;
 *      "Apple"/"Google" OAuth shortcuts straight to Home.
 *   2. **PickIdentity** — edit display name, pick avatar from the server-issued
 *      starter pack. "Continue" patches the profile then advances to step 3.
 *      "Skip" marks onboarded and navigates Home with no profile mutation.
 *   3. **HowItWorks** — informational; "Take a seat" marks onboarded and goes Home.
 *
 * **Why auth fires on step 1, not the last step:** step 2 needs a real
 * profile + the authed `/v1/avatars` endpoint. So we anon-auth on guest-tap
 * and kick off profile observation + avatar-pack fetch in parallel as we
 * advance. Each has a 3-second timeout; either falls back to client-only
 * data so the user isn't blocked by a slow network.
 *
 * **Fallbacks (intentional):**
 *  - Display name: starts as a [DisplayNameSuggester] suggestion. If the
 *    server profile arrives within 3s and the user hasn't typed, we
 *    overwrite with `profile.displayName`. After that, user input wins.
 *  - Avatar pack: 3s timeout → hardcoded V1 starter list.
 *  - Profile save on Continue: any failure other than name-taken /
 *    invalid-name is swallowed and we advance anyway — we don't want a
 *    server hiccup to dead-end the user on the last step before Home.
 *
 * Hard guard on init: if `AppData.hasUserOnboarded` is already true, fire
 * [OnboardingEvent.NavigateToHome] immediately so a returning user that
 * lands on the route bounces to Home.
 */
@Inject
class OnboardingViewModel(
    private val appCache: AppCache,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    appConfigMap: AppConfigMap,
) : SEAViewModel<OnboardingState, OnboardingEvent, OnboardingAction>(
    initialStateArg = run {
        val cfg = IdentityFeatureConfig(appConfigMap)
        OnboardingState(
            displayName = DisplayNameSuggester.next(),
            googleEnabled = cfg.googleSignInEnabled,
            appleEnabled = cfg.appleSignInEnabled && BuildInfo.isiOS(),
        )
    },
) {

    init {
        viewModelScope.launch {
            Catching {
                if (appCache.get().hasUserOnboarded) {
                    sendEvent(OnboardingEvent.NavigateToHome)
                }
            }.logOnFailure { "Onboarded-guard cache read failed" }
        }
        // Warm up the data PickIdentity needs while the Welcome screen is
        // still on the user's eyes. Anonymous sign-in starts at app launch
        // (SupabaseAuthRepositoryImpl.init), and the network client queues
        // /v1/me + /v1/avatars behind the JWT — so by the time the user
        // taps Continue and we transition to PickIdentity, both results
        // are usually already in state and the 3s timeouts never trigger.
        takeAction(OnboardingAction.WarmUp)
    }

    override suspend fun handleAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.WarmUp -> {
                action.kickOffProfileLoad()
                // No avatar-pack fetch here: the picker uses the
                // hardcoded starter pack instead so onboarding never
                // blocks on the authed `/v1/avatars` call. EditProfile
                // is the only screen that pulls the server-driven
                // pack, gated by the session-aware cache there.
            }
            OnboardingAction.ContinueAsGuest -> action.handleContinueAsGuest()
            is OnboardingAction.SignInWithOAuth -> action.handleOAuth(action.provider)
            is OnboardingAction.DisplayNameChanged -> action.updateState {
                it.copy(displayName = action.value, userEditedName = true)
            }
            OnboardingAction.RegenerateDisplayName -> action.updateState {
                it.copy(displayName = DisplayNameSuggester.next(), userEditedName = true)
            }
            is OnboardingAction.SelectAvatar -> action.updateState {
                it.copy(
                    selectedEmoji = action.emoji,
                    selectedBackgroundColor = action.backgroundColorHex,
                )
            }
            OnboardingAction.ContinueFromPickIdentity -> action.handleContinueFromPickIdentity()
            OnboardingAction.Skip -> action.handleSkip()
            OnboardingAction.Finish -> action.handleFinish()
            OnboardingAction.DismissError -> action.updateState {
                it.copy(authError = null, saveError = null)
            }
        }
    }

    private suspend fun OnboardingAction.handleContinueAsGuest() {
        updateState { it.copy(isAuthing = true, authError = null) }
        // Auth was already warmed up at app launch
        // (SupabaseAuthRepositoryImpl.init → AuthBootstrap → anon sign-in),
        // so `retry()` typically returns the cached Authenticated state
        // immediately. We only land in the Unauthenticated branch when
        // that initial resolve actually failed.
        when (val resolved = authRepository.retry()) {
            is AuthState.Authenticated -> updateState {
                it.copy(isAuthing = false, step = OnboardingStep.PickIdentity)
            }
            is AuthState.Unauthenticated -> updateState {
                it.copy(isAuthing = false, authError = describeFailure(resolved.cause))
            }
        }
    }

    /**
     * Fire-and-forget: when the profile arrives (or times out), update
     * the name + avatar fields. Captures the action receiver so the
     * background coroutine can route updates through [updateState] like
     * any other action handler — preserves UDF.
     */
    private fun OnboardingAction.kickOffProfileLoad() {
        val action = this
        viewModelScope.launch {
            val profile = Catching {
                withTimeoutOrNull(PROFILE_TIMEOUT) {
                    profileRepository.observe()
                        .filterIsInstance<Profile.Authenticated>()
                        .first()
                }
            }.getOrNull()
            if (profile != null) {
                action.updateState { current ->
                    if (current.userEditedName) {
                        current
                    } else {
                        current.copy(
                            displayName = profile.displayName,
                            selectedEmoji = current.selectedEmoji ?: profile.avatarEmoji,
                            selectedBackgroundColor = current.selectedBackgroundColor
                                ?: profile.avatarBackgroundColor,
                        )
                    }
                }
            }
        }
    }

    private suspend fun OnboardingAction.handleOAuth(provider: OAuthProvider) {
        updateState { it.copy(oauthInFlight = provider, authError = null) }
        when (val outcome = authRepository.signInWithOAuth(provider)) {
            is SignInOutcome.Success -> {
                appCache.update { it.copy(hasUserOnboarded = true) }
                updateState { it.copy(oauthInFlight = null) }
                sendEvent(OnboardingEvent.NavigateToHome)
            }
            SignInOutcome.Cancelled -> updateState { it.copy(oauthInFlight = null) }
            SignInOutcome.ProviderNotEnabled -> updateState {
                it.copy(oauthInFlight = null, authError = "That sign-in option isn't available yet.")
            }
            is SignInOutcome.NetworkError -> updateState {
                it.copy(
                    oauthInFlight = null,
                    authError = "Couldn't reach the server. Check your connection.",
                )
            }
            SignInOutcome.InvalidCredentials,
            is SignInOutcome.EmailNotConfirmed,
            is SignInOutcome.Unknown,
            -> updateState {
                it.copy(oauthInFlight = null, authError = "Sign in failed. Please try again.")
            }
        }
    }

    private suspend fun OnboardingAction.handleContinueFromPickIdentity() {
        val current = state
        updateState { it.copy(isSavingProfile = true, saveError = null) }
        val name = current.displayName.trim().takeIf { it.isNotEmpty() }
        val outcome = profileRepository.update(
            displayName = name,
            avatarEmoji = current.selectedEmoji,
            avatarBackgroundColor = current.selectedBackgroundColor,
        )
        when (outcome) {
            is UpdateProfileOutcome.Success -> updateState {
                it.copy(isSavingProfile = false, step = OnboardingStep.HowItWorks)
            }
            UpdateProfileOutcome.DisplayNameTaken -> updateState {
                it.copy(isSavingProfile = false, saveError = "That name is taken. Try another.")
            }
            UpdateProfileOutcome.InvalidDisplayName -> updateState {
                it.copy(
                    isSavingProfile = false,
                    saveError = "Try a different name — letters and numbers only.",
                )
            }
            // For everything else (NotSignedIn / NetworkError / Unknown / etc.)
            // we'd rather move forward than dead-end the user on the last form
            // step before Home. Server-side default values keep the profile
            // usable; the user can fix their name later from Profile.
            else -> updateState {
                it.copy(isSavingProfile = false, step = OnboardingStep.HowItWorks)
            }
        }
    }

    private suspend fun OnboardingAction.handleSkip() {
        appCache.update { it.copy(hasUserOnboarded = true) }
        sendEvent(OnboardingEvent.NavigateToHome)
    }

    private suspend fun OnboardingAction.handleFinish() {
        appCache.update { it.copy(hasUserOnboarded = true) }
        sendEvent(OnboardingEvent.NavigateToHome)
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
            "jwt" in msg || "invalid api key" in msg ->
                "The Supabase anon key looks wrong or expired. Check IdentityConfig."
            "unable to resolve host" in msg ||
                "failed to connect" in msg ||
                "network is unreachable" in msg ||
                "timeout" in msg ->
                "Couldn't reach the server. Check your connection and try again."
            else -> "Couldn't reach the server. Check your connection and try again."
        }
        return if (BuildInfo.isDebug && cause != null) {
            val raw = (cause.message ?: cause::class.simpleName.orEmpty()).take(200)
            "$friendly\n\nDEBUG: $raw"
        } else {
            friendly
        }
    }

    companion object {
        internal const val STARTER_TILE_COUNT = 8
        private val PROFILE_TIMEOUT = 3.seconds

        /**
         * The starter pack onboarding shows. Hardcoded so the picker
         * renders instantly with zero network — onboarding stays
         * fast even on a cold install + bad network. EditProfile
         * (post-onboarding) pulls the full server-driven pack via the
         * session-aware cache on `ProfileRepository`.
         */
        internal val STARTER_PACK: List<AvatarOption> = listOf(
            AvatarOption("🦊", "#E48A58"),
            AvatarOption("😀", "#E4C658"),
            AvatarOption("🐼", "#7D8794"),
            AvatarOption("🐯", "#C68A3D"),
            AvatarOption("🦄", "#C658E4"),
            AvatarOption("🐸", "#5DA15D"),
            AvatarOption("🦁", "#E4A258"),
            AvatarOption("🌶️", "#5DAE5D"),
        )
    }
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val isAuthing: Boolean = false,
    val oauthInFlight: OAuthProvider? = null,
    val authError: String? = null,

    val displayName: String = "",
    /** True once the user has typed in the name field — gates profile prefill. */
    val userEditedName: Boolean = false,

    val selectedEmoji: String? = null,
    val selectedBackgroundColor: String? = null,
    /**
     * Hardcoded starter pack the user picks from on the PickIdentity
     * step. Never null / loading — onboarding doesn't fetch from the
     * server (that would force the picker to wait on an authed call
     * during the most fragile bit of the first-time experience).
     * EditProfile (post-onboarding) pulls the full pack catalog.
     */
    val starterPack: List<AvatarOption> = OnboardingViewModel.STARTER_PACK,

    val isSavingProfile: Boolean = false,
    val saveError: String? = null,

    val googleEnabled: Boolean = false,
    val appleEnabled: Boolean = false,
) {
    val showOAuthRow: Boolean get() = googleEnabled || appleEnabled
}

sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data object PickIdentity : OnboardingStep
    data object HowItWorks : OnboardingStep
}

data class AvatarOption(
    val emoji: String,
    val backgroundColorHex: String?,
)

sealed interface OnboardingEvent {
    data object NavigateToHome : OnboardingEvent
}

sealed interface OnboardingAction {
    /** Self-dispatched at VM init to warm up the profile load. */
    data object WarmUp : OnboardingAction
    data object ContinueAsGuest : OnboardingAction
    data class SignInWithOAuth(val provider: OAuthProvider) : OnboardingAction
    data class DisplayNameChanged(val value: String) : OnboardingAction
    data object RegenerateDisplayName : OnboardingAction
    data class SelectAvatar(val emoji: String, val backgroundColorHex: String?) : OnboardingAction
    data object ContinueFromPickIdentity : OnboardingAction
    data object Skip : OnboardingAction
    data object Finish : OnboardingAction
    data object DismissError : OnboardingAction
}
