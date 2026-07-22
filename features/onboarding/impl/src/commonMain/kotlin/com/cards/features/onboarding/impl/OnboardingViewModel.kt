package com.dangerfield.cards.features.onboarding.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.LegalUrls
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.OnboardingSuggestedName
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential
import com.dangerfield.cards.libraries.identity.auth.awaitCredential
import com.dangerfield.cards.libraries.identity.auth.AuthOutcome
import com.dangerfield.cards.libraries.identity.auth.AuthOutcomeClassifier
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
import kotlin.time.TimeSource

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
    private val authOutcomeClassifier: AuthOutcomeClassifier,
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

    private val logger = KLog.withTag("OnboardingFlow")

    private val onboardingStartedAt = TimeSource.Monotonic.markNow()

    /**
     * True once this instance routed to Home (completed, or a returning user
     * bounced/signed in) — suppresses the best-effort `onboarding.abandoned`
     * fired from [onCleared].
     */
    private var exitedToHome = false

    init {
        // Resolve where onboarding opens (Home bounce / identity re-entry /
        // Welcome landing) through the action loop so state updates run in an
        // action scope — mirrors VerifyEmail's init → ResolveEmailFromSession.
        takeAction(OnboardingAction.ResolveEntry)
    }

    private suspend fun OnboardingAction.handleResolveEntry() {
        Catching {
            val authed = authRepository.current() as? AuthState.Authenticated
            when {
                appCache.get().hasUserOnboarded -> {
                    exitedToHome = true
                    sendEvent(OnboardingEvent.NavigateToHome)
                }
                // A real (non-anonymous) account that hasn't finished onboarding
                // is a just-verified email signup bounced back here from
                // VerifyEmail. Drop them straight into identity setup (name +
                // avatar + starter grant) like the OAuth-new path, skipping the
                // guest/OAuth landing they don't need. identityClaimed suppresses
                // the back-to-Welcome control.
                authed != null && !authed.isAnonymous -> {
                    logStepViewed(OnboardingStep.PickIdentity)
                    updateState {
                        it.copy(step = OnboardingStep.PickIdentity, identityClaimed = true)
                    }
                }
                // An anonymous guest session already exists at launch. Guest
                // creation is deferred (a new user is Unauthenticated here), so a
                // live anonymous session can only be a PRIOR guest whose
                // hasUserOnboarded flag was lost — not a new user. Re-running
                // new-user onboarding for them re-minted the identity and replayed
                // a stale level-up for progression they already had (AUTH-28).
                // Treat them as returning: adopt the onboarded flag and go Home.
                authed != null -> {
                    logger.logEvent("onboarding.auth_selected", "method" to "guest", "returning" to true)
                    appCache.update { it.copy(hasUserOnboarded = true) }
                    exitedToHome = true
                    sendEvent(OnboardingEvent.NavigateToHome)
                }
                else -> logger.logEvent("onboarding.step_viewed", "step" to "welcome")
            }
        }.logOnFailure { "Onboarding entry resolution failed" }
    }

    override suspend fun handleAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.ResolveEntry -> action.handleResolveEntry()
            OnboardingAction.ContinueAsGuest -> action.handleContinueAsGuest()
            OnboardingAction.SignIn -> sendEvent(OnboardingEvent.NavigateToSignIn)
            OnboardingAction.SignUp -> sendEvent(OnboardingEvent.NavigateToSignUp)
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
        }
    }

    private suspend fun OnboardingAction.handleContinueAsGuest() {
        // No auth here anymore — the guest account is created later, when the
        // user commits their identity (PickIdentity → Continue). Tapping
        // "Continue as guest" just enters the identity step.
        recordLegalConsent()
        logger.logEvent("onboarding.auth_selected", "method" to "guest", "returning" to false)
        logStepViewed(OnboardingStep.PickIdentity)
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
                val authOutcome = authOutcomeClassifier.classify()
                logger.logEvent(
                    "onboarding.auth_selected",
                    "method" to provider.name.lowercase(),
                    "returning" to (authOutcome != AuthOutcome.SignedUp),
                )
                routeAfterSignIn(authOutcome)
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
     * Route a fresh (non-link) sign-in by its typed [AuthOutcome]:
     *  - [AuthOutcome.SignedUp] — first-ever sign-in for this identity: run them
     *    through the rest of onboarding (PickIdentity -> grant reveal) like a
     *    guest, so they pick a name/avatar and see the starter grant instead of
     *    landing cold on Home. `identityClaimed` suppresses the back-to-Welcome
     *    control (the sign-in options no longer apply).
     *  - [AuthOutcome.SignedIn] — returning account already has a profile +
     *    wallet, so skip onboarding straight to Home.
     *
     * [AuthOutcome.Linked] can't reach here (Welcome has no anonymous guest to
     * link onto — guest creation is deferred), so it routes Home like any
     * already-established account.
     */
    private suspend fun OnboardingAction.routeAfterSignIn(outcome: AuthOutcome) {
        when (outcome) {
            AuthOutcome.SignedUp -> {
                logStepViewed(OnboardingStep.PickIdentity)
                updateState {
                    it.copy(
                        oauthInFlight = null,
                        step = OnboardingStep.PickIdentity,
                        identityClaimed = true,
                    )
                }
            }
            AuthOutcome.SignedIn, AuthOutcome.Linked -> {
                appCache.update { it.copy(hasUserOnboarded = true) }
                updateState { it.copy(oauthInFlight = null) }
                exitedToHome = true
                sendEvent(OnboardingEvent.NavigateToHome)
            }
        }
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
        // Two shapes of "Continue with Apple", decided by whether we're holding an
        // anonymous guest to link onto:
        //   1. Anonymous guest present → LINK Apple to it (keeps chips/XP) and
        //      carry on through onboarding like any new signup.
        //   2. No anonymous guest (e.g. right after account deletion, which tears
        //      the session down and suppresses guest self-heal) → SIGN IN. That
        //      sign-in may hit a pre-existing account OR mint a net-new one, so we
        //      don't assume — [signInApple] classifies via the brand-new signal.
        val isAnonymousGuest =
            (authRepository.current() as? AuthState.Authenticated)?.isAnonymous == true
        if (isAnonymousGuest) {
            when (authRepository.linkAppleIdentity(credential)) {
                LinkIdentityOutcome.Success -> {
                    recordLegalConsent()
                    logger.logEvent("onboarding.auth_selected", "method" to "apple", "returning" to false)
                    logStepViewed(OnboardingStep.PickIdentity)
                    updateState {
                        it.copy(
                            oauthInFlight = null,
                            step = OnboardingStep.PickIdentity,
                            identityClaimed = true,
                        )
                    }
                }
                LinkIdentityOutcome.AlreadyOnAnotherAccount -> signInApple(credential)
                else -> failAppleSignIn()
            }
        } else {
            signInApple(credential)
        }
    }

    /**
     * Apple sign-in with no guest to link onto. `signInWithApple` either signs
     * into a pre-existing Apple account OR mints a net-new one (Supabase creates
     * on first Apple OIDC — e.g. after an account deletion left no session). We
     * must NOT assume "existing": classify via [authOutcomeClassifier] exactly
     * like [handleOAuth], so a net-new account runs through onboarding
     * (PickIdentity + starter-grant reveal) instead of getting dumped cold on Home.
     */
    private suspend fun OnboardingAction.signInApple(credential: AppleSignInCredential) {
        if (authRepository.signInWithApple(credential) !is SignInOutcome.Success) {
            failAppleSignIn()
            return
        }
        recordLegalConsent()
        val outcome = authOutcomeClassifier.classify()
        logger.logEvent(
            "onboarding.auth_selected",
            "method" to "apple",
            "returning" to (outcome != AuthOutcome.SignedUp),
        )
        routeAfterSignIn(outcome)
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
        logStepViewed(OnboardingStep.HowItWorks)
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

    private suspend fun OnboardingAction.handleContinueFromHowItWorks() {
        logStepViewed(OnboardingStep.StarterGrant)
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
     * The only reachable back transition is PickIdentity → Welcome: leaving
     * PickIdentity always sets [OnboardingState.creationStarted] (and the
     * OAuth entry sets [OnboardingState.identityClaimed]), both of which pin
     * the flow forward, and Welcome is the entry step (system back exits the
     * app). Clears the Welcome-step [authError] so the landing page comes
     * back fresh.
     */
    private suspend fun OnboardingAction.handleBack() {
        val stepBefore = state.step
        updateState {
            if (it.step != OnboardingStep.PickIdentity || it.creationStarted || it.identityClaimed) {
                return@updateState it
            }
            it.copy(step = OnboardingStep.Welcome, authError = null)
        }
        if (state.step != stepBefore) logStepViewed(state.step)
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
            // Bounded wait — never trap the user on this screen. If creation
            // hasn't reached a terminal state in time (offline / wedged), go Home
            // degraded; the creator keeps the identity and retries in the
            // background (and GuestSessionHealer recovers it later).
            val terminal = withTimeoutOrNull(FINISH_CREATION_AWAIT_TIMEOUT) {
                guestAccountCreator.awaitTerminal()
            }
            logger.d { "finish: guest-creation terminal=${terminal?.let { it::class.simpleName } ?: "timed-out"}" }
            if (terminal is AccountCreationState.Failed || terminal == null) {
                updateState { it.copy(creationFailed = true) }
            }
        }

        exitedToHome = true
        logger.logEvent(
            "onboarding.completed",
            "duration_sec" to onboardingStartedAt.elapsedNow().inWholeSeconds,
            "account_ready" to !state.creationFailed,
        )
        appCache.update { it.copy(hasUserOnboarded = true) }
        updateState { it.copy(isFinishing = false) }
        sendEvent(OnboardingEvent.NavigateToHome)
    }

    override fun onCleared() {
        // Best-effort abandonment marker: the VM outliving the flow without ever
        // routing Home means the user backed out (system back on Welcome exits
        // the app and clears the entry). A process kill won't reach this — the
        // funnel's step_viewed-without-completed sessions cover that case.
        if (!exitedToHome) {
            logger.logEvent("onboarding.abandoned", "step" to state.step.eventName())
        }
        super.onCleared()
    }

    private fun logStepViewed(step: OnboardingStep) {
        logger.logEvent("onboarding.step_viewed", "step" to step.eventName())
    }

    private fun OnboardingStep.eventName(): String = when (this) {
        OnboardingStep.Welcome -> "welcome"
        OnboardingStep.PickIdentity -> "pick_identity"
        OnboardingStep.HowItWorks -> "how_it_works"
        OnboardingStep.StarterGrant -> "starter_grant"
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
         * Upper bound on how long "Take a seat" waits for the in-flight guest
         * account to finish before going Home anyway. Onboarding must never trap
         * the user on a backend call — if creation hasn't settled by now (offline
         * / slow), we proceed degraded and the creator keeps retrying in the
         * background. Usually already done: the grant-reveal wait above gave it a
         * head start.
         */
        private val FINISH_CREATION_AWAIT_TIMEOUT = 5_000.milliseconds

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
    data object NavigateToSignUp : OnboardingEvent
}

/**
 * Inline error surfaced under the Welcome step's primary CTAs. Typed so
 * the VM doesn't hold raw user-facing copy — `OnboardingScreen.kt`
 * resolves each variant through Compose Multiplatform resources at
 * render time. Only the OAuth/Apple entries can fail here; the guest path
 * defers account creation to the app-scoped [GuestAccountCreator], which
 * retries in the background instead of surfacing an error on Welcome.
 */
sealed interface OnboardingAuthError {
    /** OAuth provider isn't enabled in Supabase dashboard yet. */
    data object OAuthProviderNotEnabled : OnboardingAuthError
    /** OAuth network unreachable. */
    data object OAuthNetworkError : OnboardingAuthError
    /** OAuth invalid credentials / email-not-confirmed / unknown. */
    data object OAuthFailed : OnboardingAuthError
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
    /** Internal: fired once on init to resolve the opening step / bounce. */
    data object ResolveEntry : OnboardingAction
    data object ContinueAsGuest : OnboardingAction
    /** Welcome-step entry into the email/password sign-in flow. */
    data object SignIn : OnboardingAction
    /** Welcome-step entry into the email/password sign-up flow. */
    data object SignUp : OnboardingAction
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
}
