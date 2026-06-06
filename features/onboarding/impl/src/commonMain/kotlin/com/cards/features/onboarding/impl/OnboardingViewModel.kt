package com.dangerfield.cards.features.onboarding.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.DisplayNameRules
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the four-step onboarding flow:
 *   1. **Welcome** — "Continue as guest" anon-signs-in then advances to step 2;
 *      "Apple"/"Google" OAuth shortcuts straight to Home.
 *   2. **PickIdentity** — edit display name, pick avatar from the server-issued
 *      starter pack. "Continue" advances to step 3 immediately and patches the
 *      profile in the background (optimistic — no spinner).
 *   3. **HowItWorks** — informational; "Continue" advances to step 4.
 *   4. **StarterGrant** — celebratory chip-grant reveal. Observes the wallet
 *      with a short grace window: if the authoritative balance has hydrated we
 *      reveal the real number and clear [AppData.requiresGrantInfo] (so the
 *      Home dialog won't re-reveal); otherwise we show "lands when you
 *      reconnect" and leave the flag for the Home dialog to reveal later.
 *      "Take a seat" marks onboarded and goes Home.
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
 *  - Profile save on Continue: fired in the background *after* we've already
 *    advanced. A taken / invalid name surfaces on the name field (seen only
 *    if the user steps back); every other failure is swallowed. Either way
 *    the server's generated default keeps the profile usable and the user
 *    can rename later from Profile.
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
    private val chipsRepository: ChipsRepository,
    googleSignInEnabled: GoogleSignInEnabled,
    appleSignInEnabled: AppleSignInEnabled,
) : SEAViewModel<OnboardingState, OnboardingEvent, OnboardingAction>(
    initialStateArg = OnboardingState(
        displayName = DisplayNameSuggester.next(),
        googleEnabled = googleSignInEnabled(),
        appleEnabled = appleSignInEnabled() && BuildInfo.isiOS(),
    ),
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
            OnboardingAction.SignIn -> sendEvent(OnboardingEvent.NavigateToSignIn)
            OnboardingAction.Back -> action.handleBack()
            is OnboardingAction.SignInWithOAuth -> action.handleOAuth(action.provider)
            is OnboardingAction.DisplayNameChanged -> action.updateState {
                // Editing the name dismisses any stale "taken / invalid"
                // notice from the optimistic background save.
                it.copy(
                    displayName = action.value.take(MAX_DISPLAY_NAME_LENGTH),
                    userEditedName = true,
                    saveError = null,
                )
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
            OnboardingAction.ContinueFromHowItWorks -> action.handleContinueFromHowItWorks()
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
        val action = this
        val current = state
        // Optimistic: jump to the last step immediately and persist in the
        // background. The avatar always validates (it mirrors the server
        // starter pack) and we don't want a network round-trip to stall the
        // most fragile bit of the first-time flow. If the name turns out to
        // be taken / invalid we surface it on the name field — visible only
        // if the user steps back — and otherwise let the server's generated
        // default stand (they can rename later from Profile).
        updateState { it.copy(step = OnboardingStep.HowItWorks, saveError = null) }
        val name = current.displayName.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            val outcome = Catching {
                profileRepository.update(
                    displayName = name,
                    avatarEmoji = current.selectedEmoji,
                    avatarBackgroundColor = current.selectedBackgroundColor,
                )
            }.logOnFailure { "Optimistic onboarding profile update failed" }.getOrNull()
            when (outcome) {
                UpdateProfileOutcome.DisplayNameTaken -> action.updateState {
                    it.copy(saveError = OnboardingSaveError.DisplayNameTaken)
                }
                UpdateProfileOutcome.InvalidDisplayName -> action.updateState {
                    it.copy(saveError = OnboardingSaveError.InvalidDisplayName)
                }
                else -> Unit
            }
        }
    }

    /**
     * Advances HowItWorks → StarterGrant and kicks off the grant reveal.
     */
    private suspend fun OnboardingAction.handleContinueFromHowItWorks() {
        updateState { it.copy(step = OnboardingStep.StarterGrant) }
        kickOffGrantReveal()
    }

    /**
     * Reveal the starter grant truthfully. Cold-boot sync already runs at
     * launch (AppEventDispatcher → ChipsRepository.onColdBoot), so for a new
     * account the wallet is usually hydrated by the time the user reaches
     * this step; we kick another [ChipsRepository.sync] as a belt-and-
     * suspenders nudge and observe the balance with a short grace window.
     *
     *  - Balance hydrated within the window → reveal the real number and
     *    clear [AppData.requiresGrantInfo] (we've informed them; the Home
     *    welcome dialog won't re-reveal).
     *  - Timed out / offline → show "lands when you reconnect" copy and
     *    leave the flag set — the Home dialog reveals it once the wallet
     *    syncs. We never display a number we didn't get from the server.
     */
    private fun OnboardingAction.kickOffGrantReveal() {
        val action = this
        viewModelScope.launch {
            launch { Catching { chipsRepository.sync() }.logOnFailure { "Onboarding grant sync failed" } }
            val balance = Catching {
                withTimeoutOrNull(GRANT_REVEAL_TIMEOUT) {
                    chipsRepository.observeBalance().filterNotNull().first()
                }
            }.getOrNull()
            if (balance != null) {
                action.updateState { it.copy(revealedChips = balance, grantRevealTimedOut = false) }
                appCache.update { it.copy(requiresGrantInfo = false) }
            } else {
                action.updateState { it.copy(grantRevealTimedOut = true) }
            }
        }
    }

    /**
     * Steps back through the flow: StarterGrant → HowItWorks → PickIdentity
     * → Welcome. The control isn't rendered on Welcome (the entry step has
     * nothing before it; system back exits the app), so the Welcome branch
     * is a defensive no-op. Always clears the Welcome-step [authError]. Keeps
     * a [saveError] when we land back on PickIdentity — that's where the
     * name field lives, so the optimistic save's "taken / invalid" notice
     * stays visible for the user to fix; it clears once we leave the
     * identity step entirely.
     */
    private suspend fun OnboardingAction.handleBack() {
        updateState {
            val previous = when (it.step) {
                OnboardingStep.StarterGrant -> OnboardingStep.HowItWorks
                OnboardingStep.HowItWorks -> OnboardingStep.PickIdentity
                OnboardingStep.PickIdentity -> OnboardingStep.Welcome
                OnboardingStep.Welcome -> OnboardingStep.Welcome
            }
            it.copy(
                step = previous,
                authError = null,
                saveError = if (previous == OnboardingStep.PickIdentity) it.saveError else null,
            )
        }
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

        /** Max display-name length; mirrors EditProfile's cap so onboarding and
         *  edit-profile agree. Stricter than the server limit (UX clamp). */
        internal const val MAX_DISPLAY_NAME_LENGTH = DisplayNameRules.MAX_LENGTH
        private val PROFILE_TIMEOUT = 3.seconds

        /**
         * How long the StarterGrant page waits for the authoritative wallet
         * balance before falling back to the "lands when you reconnect" copy.
         * Short — the cold-boot sync has usually landed by now; this only
         * bites on a slow/offline first run.
         */
        private val GRANT_REVEAL_TIMEOUT = 1_500.milliseconds

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

    val saveError: OnboardingSaveError? = null,

    /**
     * The authoritative starter-grant balance revealed on the StarterGrant
     * step, or null until the wallet hydrates. Only ever set from a real
     * server balance — we never show a number we didn't get from the server.
     */
    val revealedChips: Long? = null,
    /**
     * True once the grant-reveal grace window elapsed without a balance
     * (slow / offline first run). The StarterGrant step then shows the
     * "lands when you reconnect" copy and the Home dialog reveals the real
     * number later.
     */
    val grantRevealTimedOut: Boolean = false,

    val googleEnabled: Boolean = false,
    val appleEnabled: Boolean = false,
) {
    val showOAuthRow: Boolean get() = googleEnabled || appleEnabled
}

sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data object PickIdentity : OnboardingStep
    data object HowItWorks : OnboardingStep
    data object StarterGrant : OnboardingStep
}

data class AvatarOption(
    val emoji: String,
    val backgroundColorHex: String?,
)

sealed interface OnboardingEvent {
    data object NavigateToHome : OnboardingEvent
    data object NavigateToSignIn : OnboardingEvent
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
    /** Welcome-step entry into the email/password sign-in flow. */
    data object SignIn : OnboardingAction
    /** Steps back to the previous onboarding step (steps 2–4 only). */
    data object Back : OnboardingAction
    data class SignInWithOAuth(val provider: OAuthProvider) : OnboardingAction
    data class DisplayNameChanged(val value: String) : OnboardingAction
    data object RegenerateDisplayName : OnboardingAction
    data class SelectAvatar(val emoji: String, val backgroundColorHex: String?) : OnboardingAction
    data object ContinueFromPickIdentity : OnboardingAction
    /** HowItWorks → StarterGrant; kicks off the grant reveal. */
    data object ContinueFromHowItWorks : OnboardingAction
    data object Finish : OnboardingAction
    data object DismissError : OnboardingAction
}
