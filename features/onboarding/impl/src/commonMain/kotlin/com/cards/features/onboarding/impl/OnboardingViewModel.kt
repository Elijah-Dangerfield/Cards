package com.dangerfield.cards.features.onboarding.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.LegalUrls
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.OnboardingSuggestedName
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential
import com.dangerfield.cards.libraries.identity.auth.awaitCredential
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.DisplayNameRules
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives the four-step onboarding flow with **deferred account creation** —
 * no account exists on launch; one is minted only when the user commits.
 *   1. **Welcome** — "Continue as guest" advances to step 2 (no auth yet);
 *      "Apple"/"Google" sign-in: a returning account goes straight to Home, a
 *      brand-new one runs through the rest of onboarding (PickIdentity -> grant)
 *      like a guest. New-vs-returning is read off the wallet-just-created signal.
 *   2. **PickIdentity** — edit display name (prefilled from the unauthed
 *      onboarding config or a client suggestion) + pick a starter-pack avatar.
 *      "Continue" kicks off guest-account creation **in the background**
 *      ([GuestAccountCreator], app-scoped so paging on doesn't cancel it) and
 *      advances to step 3. From here back is blocked — creation is in flight.
 *   3. **HowItWorks** — informational; "Continue" advances to step 4.
 *   4. **StarterGrant** — celebratory chip-grant reveal. "Take a seat" joins on
 *      the in-flight creation: ready → Home; failed (offline) → Home anyway,
 *      into the degraded "we'll keep retrying" state.
 *
 * **Why creation is deferred:** minting an anonymous account on launch left an
 * orphan + leaked its starter-grant flag whenever the user then signed into a
 * real account. Now onboarding runs entirely unauthenticated (config + client
 * fallbacks), and the account is created at the point of no return.
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
    private val guestAccountCreator: GuestAccountCreator,
    private val appleSignInCoordinator: AppleSignInCoordinator,
    private val onboardingStarterGrant: OnboardingStarterGrant,
    private val clock: Clock,
    onboardingSuggestedName: OnboardingSuggestedName,
    googleSignInEnabled: GoogleSignInEnabled,
    appleSignInEnabled: AppleSignInEnabled,
) : SEAViewModel<OnboardingState, OnboardingEvent, OnboardingAction>(
    initialStateArg = run {
        // Pre-select a random starter avatar so the user always lands with an
        // identity (and a highlighted pick in the grid) rather than a blank
        // one they might skip past — mirrors the server's own random default.
        // They can still tap a different option. One AvatarOption is picked so
        // the emoji and its paired background color stay together.
        val avatar = STARTER_PACK.random()
        OnboardingState(
            // Prefer the server-suggested name (unauthed config) when present and
            // valid; otherwise a client-side suggestion. Offline → client suggestion.
            displayName = onboardingSuggestedName.nameOrNull()?.takeIf { DisplayNameRules.isValid(it) }
                ?: DisplayNameSuggester.next(),
            googleEnabled = googleSignInEnabled(),
            appleEnabled = appleSignInEnabled() && BuildInfo.isiOS(),
            selectedEmoji = avatar.emoji,
            selectedBackgroundColor = avatar.backgroundColorHex,
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
    }

    override suspend fun handleAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.ContinueAsGuest -> action.handleContinueAsGuest()
            OnboardingAction.SignIn -> sendEvent(OnboardingEvent.NavigateToSignIn)
            OnboardingAction.Back -> action.handleBack()
            is OnboardingAction.SignInWithOAuth -> action.handleOAuth(action.provider)
            OnboardingAction.SignInWithApple -> action.handleAppleSignIn()
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
        // No auth here anymore — the guest account is created later, when the
        // user commits their identity (PickIdentity → Continue). Tapping
        // "Continue as guest" just enters the identity step.
        recordLegalConsent()
        updateState { it.copy(authError = null, step = OnboardingStep.PickIdentity) }
    }

    /**
     * Persist that the user accepted the passive "by continuing, you agree to
     * Terms + Privacy" consent shown on the Welcome step, by stamping the live
     * [LegalUrls.LEGAL_VERSION] + an acceptance timestamp into [AppCache]. Run
     * from every forward path off Welcome (guest / OAuth / Apple) — proceeding
     * is the acceptance. Idempotent: re-stamping the same version just refreshes
     * the timestamp.
     */
    private suspend fun recordLegalConsent() {
        appCache.update {
            it.copy(
                acceptedLegalVersion = LegalUrls.LEGAL_VERSION,
                legalConsentAcceptedAt = clock.now().toEpochMilliseconds(),
            )
        }
    }

    private suspend fun OnboardingAction.handleOAuth(provider: OAuthProvider) {
        updateState { it.copy(oauthInFlight = provider, authError = null) }
        when (val outcome = authRepository.signInWithOAuth(provider)) {
            is SignInOutcome.Success -> {
                recordLegalConsent()
                if (isBrandNewAccount()) {
                    // First-ever sign-in for this identity: run them through the
                    // rest of onboarding (PickIdentity -> grant reveal) like a
                    // guest, so they pick a name/avatar and see the starter grant
                    // instead of landing cold on Home. Mirrors the Apple-link new
                    // identity path. identityClaimed suppresses the back-to-Welcome
                    // control (the sign-in options no longer apply).
                    updateState {
                        it.copy(
                            oauthInFlight = null,
                            step = OnboardingStep.PickIdentity,
                            identityClaimed = true,
                        )
                    }
                } else {
                    // Returning account already has a profile + wallet — skip
                    // onboarding straight to Home.
                    appCache.update { it.copy(hasUserOnboarded = true) }
                    updateState { it.copy(oauthInFlight = null) }
                    sendEvent(OnboardingEvent.NavigateToHome)
                }
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

    /**
     * Whether the just-signed-in account is brand new (first-ever sign-in) vs.
     * a returning one. The discriminator is [ChipsRepository.walletJustCreated]:
     * the first wallet sync after a fresh account lazily creates the server
     * wallet and flips the signal true; a returning account already has a
     * wallet, so it stays false. Same signal the Home starter-grant gate keys
     * on. We kick the sync here so the answer is ready before we branch; if the
     * sync fails (offline) the signal stays false and we treat the user as
     * returning (Home) rather than trapping them in onboarding.
     */
    private suspend fun isBrandNewAccount(): Boolean {
        Catching { chipsRepository.sync() }.logOnFailure { "OAuth wallet sync failed" }
        return chipsRepository.walletJustCreated.value
    }

    /**
     * Native "Sign in with Apple". Runs the iOS coordinator for the id token,
     * then either **links** the Apple identity to the current anonymous guest
     * (preserving any chips / XP earned as a guest) or, if there's no anonymous
     * session, signs in fresh. A dismissed sheet (`null` credential) is a quiet
     * no-op; only a real failure surfaces an error. Reuses [oauthInFlight] so
     * the button shows the in-flight state like the OAuth buttons.
     */
    private suspend fun OnboardingAction.handleAppleSignIn() {
        updateState { it.copy(oauthInFlight = OAuthProvider.Apple, authError = null) }
        Catching { appleSignInCoordinator.awaitCredential() }
            .logOnFailure { "Apple credential request failed" }
            .fold(
                onSuccess = { credential ->
                    if (credential == null) {
                        // User dismissed the sheet — quiet no-op.
                        updateState { it.copy(oauthInFlight = null) }
                    } else {
                        finishAppleSignIn(credential)
                    }
                },
                onFailure = {
                    updateState {
                        it.copy(oauthInFlight = null, authError = OnboardingAuthError.OAuthFailed)
                    }
                },
            )
    }

    private suspend fun OnboardingAction.finishAppleSignIn(credential: AppleSignInCredential) {
        // We always hold an anonymous session here (anon sign-in runs on app
        // init). Two cases for "Continue with Apple":
        //   1. Brand-new Apple identity → LINK it to this guest (keeps chips/XP)
        //      and carry on through the rest of onboarding like any new signup.
        //   2. The Apple identity already belongs to an existing account → the
        //      link is rejected, so SIGN IN to that account and skip onboarding
        //      (it already has a profile). The throwaway anon is orphaned.
        val isAnonymousGuest =
            (authRepository.current() as? AuthState.Authenticated)?.isAnonymous == true
        if (isAnonymousGuest) {
            when (authRepository.linkAppleIdentity(credential)) {
                LinkIdentityOutcome.Success -> {
                    recordLegalConsent()
                    updateState {
                        it.copy(
                            oauthInFlight = null,
                            step = OnboardingStep.PickIdentity,
                            identityClaimed = true,
                        )
                    }
                }
                LinkIdentityOutcome.AlreadyOnAnotherAccount -> enterExistingAppleAccount(credential)
                else -> failAppleSignIn()
            }
        } else {
            enterExistingAppleAccount(credential)
        }
    }

    /** Existing-account path: switch sessions, mark onboarded, jump to Home. */
    private suspend fun OnboardingAction.enterExistingAppleAccount(credential: AppleSignInCredential) {
        if (authRepository.signInWithApple(credential) is SignInOutcome.Success) {
            // Switched to a pre-existing account. No grant suppression needed
            // anymore — the Home gate keys on the live walletJustCreated signal,
            // which is false for an account whose wallet already existed.
            recordLegalConsent()
            appCache.update { it.copy(hasUserOnboarded = true) }
            updateState { it.copy(oauthInFlight = null) }
            sendEvent(OnboardingEvent.NavigateToHome)
        } else {
            failAppleSignIn()
        }
    }

    private suspend fun OnboardingAction.failAppleSignIn() =
        updateState { it.copy(oauthInFlight = null, authError = OnboardingAuthError.OAuthFailed) }

    private suspend fun OnboardingAction.handleContinueFromPickIdentity() {
        val action = this
        val current = state
        val identity = PendingIdentity(
            displayName = current.displayName.trim().takeIf { it.isNotEmpty() },
            avatarEmoji = current.selectedEmoji,
            avatarBackgroundColor = current.selectedBackgroundColor,
        )
        // Point of no return: advance and mark creation started so back is
        // blocked from here (the chosen name is now committed to creation).
        updateState { it.copy(step = OnboardingStep.HowItWorks, saveError = null, creationStarted = true) }

        if (authRepository.current() is AuthState.Authenticated) {
            // A real account already exists (e.g. claimed via OAuth before
            // reaching this step) — just patch it in the background, surfacing
            // name conflicts on the field (visible only if they could step back).
            viewModelScope.launch {
                val outcome = Catching {
                    profileRepository.update(
                        displayName = identity.displayName,
                        avatarEmoji = identity.avatarEmoji,
                        avatarBackgroundColor = identity.avatarBackgroundColor,
                    )
                }.logOnFailure { "Onboarding profile update failed" }.getOrNull()
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
        } else {
            // Guest path: mint the account in the background (app scope). The
            // final step joins on the result.
            guestAccountCreator.start(identity)
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
     * Reveal the starter grant. The guest account was created on the previous
     * step, so a [ChipsRepository.sync] usually has the authoritative balance
     * ready; we kick one and observe with a short grace window.
     *
     *  - Real balance within the window → reveal it.
     *  - Otherwise fall back to the server-advertised config amount
     *    ([OnboardingStarterGrant]) if we have it — it equals what the server
     *    seeds, so it's not a made-up number; useful when the account isn't
     *    live yet (offline → degraded).
     *  - Neither available → "lands when you reconnect" copy.
     *
     * Either reveal marks [AppData.didSeeInitialGrantInOnboarding] so the Home
     * welcome dialog won't show the number a second time.
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
            val amount = balance ?: onboardingStarterGrant.amountOrNull()
            if (amount != null) {
                action.updateState { it.copy(revealedChips = amount, grantRevealTimedOut = false) }
                appCache.update { it.copy(didSeeInitialGrantInOnboarding = true) }
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
            // Once account creation has kicked off (or an identity was claimed),
            // there's no going back — the account is forming and the Welcome
            // sign-in options no longer apply. Back is a no-op from there.
            if (it.creationStarted || it.identityClaimed) return@updateState it
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

    /**
     * "Take a seat": join on the in-flight guest creation, then go Home.
     *  - Already authenticated (came in via OAuth) → nothing to await.
     *  - Guest creation Succeeded → Home with the account live.
     *  - Guest creation Failed (offline) → Home anyway, flagged degraded; the
     *    creator retains the identity and retries when back online.
     * We always proceed to Home — never trap the user on the grant screen.
     */
    private suspend fun OnboardingAction.handleFinish() {
        updateState { it.copy(isFinishing = true) }

        val alreadyAuthed = authRepository.current() is AuthState.Authenticated
        if (!alreadyAuthed && guestAccountCreator.state.value != AccountCreationState.Idle) {
            val terminal = guestAccountCreator.awaitTerminal()
            if (terminal is AccountCreationState.Failed) {
                updateState { it.copy(creationFailed = true) }
            }
        }

        appCache.update { it.copy(hasUserOnboarded = true) }
        updateState { it.copy(isFinishing = false) }
        sendEvent(OnboardingEvent.NavigateToHome)
    }

    companion object {
        internal const val STARTER_TILE_COUNT = 8

        /** Max display-name length; mirrors EditProfile's cap so onboarding and
         *  edit-profile agree. Stricter than the server limit (UX clamp). */
        internal const val MAX_DISPLAY_NAME_LENGTH = DisplayNameRules.MAX_LENGTH

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

    /**
     * True once the user has claimed a real identity mid-onboarding (e.g. a new
     * Apple account) and is finishing setup. Suppresses the "back to landing
     * page" affordance — the Welcome sign-in options are meaningless once you're
     * signed in, and re-running them would be confusing.
     */
    val identityClaimed: Boolean = false,

    /**
     * True once guest-account creation has been kicked off (PickIdentity →
     * Continue). Blocks back-navigation from there — the chosen name is
     * committed and the Welcome sign-in options no longer apply.
     */
    val creationStarted: Boolean = false,

    /** True while the final step is joining on the in-flight account creation. */
    val isFinishing: Boolean = false,

    /**
     * True if guest-account creation failed (offline) by the time the user
     * finished onboarding. They still land on Home; the account is retried in
     * the background and the degraded experience explains the limited state.
     */
    val creationFailed: Boolean = false,

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
)

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
    data object ContinueAsGuest : OnboardingAction
    /** Welcome-step entry into the email/password sign-in flow. */
    data object SignIn : OnboardingAction
    /** Steps back to the previous onboarding step (steps 2–4 only). */
    data object Back : OnboardingAction
    data class SignInWithOAuth(val provider: OAuthProvider) : OnboardingAction
    /** Welcome-step native "Sign in with Apple" (iOS only). */
    data object SignInWithApple : OnboardingAction
    data class DisplayNameChanged(val value: String) : OnboardingAction
    data object RegenerateDisplayName : OnboardingAction
    data class SelectAvatar(val emoji: String, val backgroundColorHex: String?) : OnboardingAction
    data object ContinueFromPickIdentity : OnboardingAction
    /** HowItWorks → StarterGrant; kicks off the grant reveal. */
    data object ContinueFromHowItWorks : OnboardingAction
    data object Finish : OnboardingAction
    data object DismissError : OnboardingAction
}
