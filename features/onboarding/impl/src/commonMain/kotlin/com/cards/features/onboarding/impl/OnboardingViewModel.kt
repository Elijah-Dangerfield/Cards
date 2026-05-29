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
        // (SupabaseAuthRepositoryImpl.init); WarmUp queues /v1/me behind
        // that JWT so the prefill is ready by the time the user taps
        // Continue. The avatar pack is warmed by ProfileRepositoryImpl
        // at app boot (AutoInit) — onboarding doesn't need to fire it
        // here.
        takeAction(OnboardingAction.WarmUp)
    }

    override suspend fun handleAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.WarmUp -> {
                action.kickOffProfileLoad()
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
                it.copy(isAuthing = false, authError = describeGuestFailure(resolved.cause))
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
                it.copy(oauthInFlight = null, authError = OnboardingAuthError.OAuthProviderNotEnabled)
            }
            is SignInOutcome.NetworkError -> updateState {
                it.copy(oauthInFlight = null, authError = OnboardingAuthError.OAuthNetworkError)
            }
            SignInOutcome.InvalidCredentials,
            is SignInOutcome.EmailNotConfirmed,
            is SignInOutcome.Unknown,
            -> updateState {
                it.copy(oauthInFlight = null, authError = OnboardingAuthError.OAuthFailed)
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
                it.copy(isSavingProfile = false, saveError = OnboardingSaveError.DisplayNameTaken)
            }
            UpdateProfileOutcome.InvalidDisplayName -> updateState {
                it.copy(
                    isSavingProfile = false,
                    saveError = OnboardingSaveError.InvalidDisplayName,
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
     * Map the underlying guest-sign-in exception onto a typed [OnboardingAuthError]
     * variant. Pattern-matches against the most common Supabase responses;
     * falls back to a generic variant for the rest. The optional
     * `debugDetails` payload carries through the exception message so the
     * resolver in `OnboardingScreen.kt` can append a `DEBUG:` suffix on
     * debug builds without putting that branching in the VM.
     */
    private fun describeGuestFailure(cause: Throwable?): OnboardingAuthError {
        val msg = cause?.message.orEmpty().lowercase()
        val debugDetails = (cause?.message ?: cause?.let { it::class.simpleName.orEmpty() })
            ?.takeIf { it.isNotEmpty() }
            ?.take(200)
        return when {
            "anonymous" in msg && ("disabled" in msg || "not enabled" in msg || "not allowed" in msg) ->
                OnboardingAuthError.AnonymousSignInDisabled(debugDetails)
            "captcha" in msg ->
                OnboardingAuthError.CaptchaRequired(debugDetails)
            "jwt" in msg || "invalid api key" in msg ->
                OnboardingAuthError.InvalidConfig(debugDetails)
            else ->
                OnboardingAuthError.GuestSignInFailed(debugDetails)
        }
    }

    companion object {
        internal const val STARTER_TILE_COUNT = 8
        private val PROFILE_TIMEOUT = 3.seconds

        /**
         * The starter pack onboarding shows. Deliberately basic —
         * six common animals + two poker-themed entries — so the
         * cooler themed unlock packs (Animals/Food/Sports/Fantasy/
         * Mythical) feel like a real upgrade. Mirrors the server's
         * `AvatarPacks.Starter` exactly so a patchMe with any of
         * these emojis + colors is never rejected. Server contract
         * is append-only: emojis here are guaranteed to keep
         * validating even on old APKs after the server-side list
         * grows.
         *
         * Colors come from the server `AvatarPalette` so each
         * default selection is also accepted by patchMe's
         * background-color validation.
         *
         * Hardcoded so the picker renders instantly with zero
         * network — onboarding stays fast even on a cold install
         * + bad network. EditProfile (post-onboarding) pulls the
         * full server-driven pack via the session-aware cache on
         * `ProfileRepository`.
         */
        internal val STARTER_PACK: List<AvatarOption> = listOf(
            AvatarOption("🦊", "#ff6b35"),
            AvatarOption("🐱", "#ffc857"),
            AvatarOption("🐼", "#37d5c2"),
            AvatarOption("🐯", "#a18bff"),
            AvatarOption("🐸", "#5bc79b"),
            AvatarOption("🦁", "#ff5da2"),
            AvatarOption("🃏", "#7555ff"),
            AvatarOption("🎲", "#52a2ff"),
        )
    }
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val isAuthing: Boolean = false,
    val oauthInFlight: OAuthProvider? = null,
    val authError: OnboardingAuthError? = null,

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
    val saveError: OnboardingSaveError? = null,

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

/**
 * Inline error surfaced under the Welcome step's primary CTAs. Typed so
 * the VM doesn't hold raw user-facing copy — `OnboardingScreen.kt`
 * resolves each variant through Compose Multiplatform resources at
 * render time. The four `data class` variants from the guest-sign-in
 * path carry an optional `debugDetails` payload so the resolver can
 * append a `DEBUG:` suffix on debug builds without dragging the
 * branching into the VM.
 */
sealed interface OnboardingAuthError {
    /** OAuth provider isn't enabled in Supabase dashboard yet. */
    data object OAuthProviderNotEnabled : OnboardingAuthError
    /** OAuth network unreachable. */
    data object OAuthNetworkError : OnboardingAuthError
    /** OAuth invalid credentials / email-not-confirmed / unknown. */
    data object OAuthFailed : OnboardingAuthError

    /** Guest path: Supabase project has anonymous sign-in disabled. */
    data class AnonymousSignInDisabled(val debugDetails: String?) : OnboardingAuthError
    /** Guest path: project requires captcha. */
    data class CaptchaRequired(val debugDetails: String?) : OnboardingAuthError
    /** Guest path: Supabase anon key looks wrong or expired. */
    data class InvalidConfig(val debugDetails: String?) : OnboardingAuthError
    /** Guest path: network unreachable or generic failure (shared copy). */
    data class GuestSignInFailed(val debugDetails: String?) : OnboardingAuthError
}

/**
 * Inline error surfaced under the PickIdentity step's display-name field.
 * Both variants come from the profile-update outcome; everything else is
 * intentionally swallowed so the user isn't dead-ended.
 */
sealed interface OnboardingSaveError {
    data object DisplayNameTaken : OnboardingSaveError
    data object InvalidDisplayName : OnboardingSaveError
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
