package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.OnboardingAttempt
import com.dangerfield.cards.libraries.core.LegalUrls
import com.dangerfield.cards.libraries.core.logging.EXTRA_APP_EVENT
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogTree
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.OnboardingSuggestedName
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthOutcome
import com.dangerfield.cards.libraries.identity.auth.AuthOutcomeClassifier
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Covers the **deferred-creation** onboarding flow:
 *
 *  - Returning user (hasUserOnboarded) bounces straight to Home.
 *  - ContinueAsGuest just enters PickIdentity — no auth, no account yet.
 *  - ContinueFromPickIdentity kicks off guest-account creation in the
 *    background (the app-scoped [GuestAccountCreator]) and locks back-nav.
 *    When an account already exists (OAuth), it patches the profile instead.
 *  - StarterGrant reveals the wallet balance once it hydrates.
 *  - Finish joins on the in-flight creation: success or failure both go Home
 *    (failure flags the degraded state).
 *  - Back is blocked once creation has started or an identity is claimed.
 *  - SignInWithOAuth: returning account → onboarded + Home; brand-new account
 *    (wallet just created) → run through PickIdentity; cancel/failure handled.
 *  - SignInWithApple: anonymous guest links the identity (keeps progress);
 *    identity-on-another-account signs in and skips onboarding; a dismissed
 *    sheet is a quiet no-op; a native failure surfaces OAuthFailed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest : CoroutineTest() {

    @Test
    fun init_alreadyOnboarded_immediatelyNavigatesHome() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = newVm(cache = cache)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        runCurrent()

        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun init_authenticatedNotOnboarded_opensAtIdentity_claimed() = runUnitTest {
        // A just-confirmed email signup routed back from VerifyEmail: a real
        // (non-anonymous) account that hasn't finished onboarding opens at
        // PickIdentity with the back-to-Welcome control suppressed — not the
        // guest/OAuth landing it no longer needs.
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = false))
        val auth = FakeAuthRepository(initialAuthState = sampleAuthenticated)
        val vm = newVm(cache = cache, auth = auth)
        runCurrent()

        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertTrue(vm.state.identityClaimed)
    }

    @Test
    fun init_returningAnonymousGuest_skipsOnboarding_navigatesHome() = runUnitTest {
        // AUTH-28: an install with a pre-existing anonymous guest session that
        // reaches onboarding-not-onboarded is a RETURNING guest whose flag was
        // lost — not a new user. It must skip new-user onboarding (which re-minted
        // the identity and replayed a stale level-up) and go straight Home,
        // adopting the onboarded flag so it can't bounce back here.
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = false))
        val auth = FakeAuthRepository(initialAuthState = sampleAnonymousGuest)
        val vm = newVm(cache = cache, auth = auth)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        runCurrent()

        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
        assertTrue(cache.get().hasUserOnboarded)
        assertEquals(OnboardingStep.Welcome, vm.state.step, "must not drop into new-user identity setup")
    }

    @Test
    fun starterPack_pinsCanonicalEmojis_inSyncWithServerStarter() {
        val expectedEmojis = listOf("🦊", "🐱", "🐼", "🐯", "🐸", "🦁", "🃏", "🎲")
        assertEquals(expectedEmojis, OnboardingViewModel.STARTER_PACK.map { it.emoji })
        assertEquals(OnboardingViewModel.STARTER_TILE_COUNT, OnboardingViewModel.STARTER_PACK.size)
    }

    @Test
    fun starterPack_defaultBackgroundColors_matchServerPaletteForm() {
        OnboardingViewModel.STARTER_PACK.forEach { option ->
            val color = option.backgroundColorHex
                ?: error("STARTER_PACK ${option.emoji} is missing a default background color")
            assertTrue(
                color.matches(Regex("^#[0-9a-f]{6}$")),
                "STARTER_PACK color $color for ${option.emoji} " +
                    "must be lowercase #rrggbb — server's AvatarPalette is lowercase only",
            )
        }
    }

    @Test
    fun continueAsGuest_entersPickIdentity_withoutAuthing() = runUnitTest {
        // No account is created here anymore — guest-tap just enters the
        // identity step; creation is deferred to ContinueFromPickIdentity.
        val auth = FakeAuthRepository() // Unauthenticated by default
        val vm = newVm(auth = auth)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertNull(vm.state.authError)
        assertEquals(OnboardingViewModel.STARTER_TILE_COUNT, vm.state.starterPack.size)
        // Display name is prefilled from a client suggestion (config empty in tests).
        assertTrue(vm.state.displayName.isNotBlank())
    }

    @Test
    fun displayNameChanged_setsValue_andUserEditedFlag() = runUnitTest {
        val vm = newVm()
        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        vm.takeAction(OnboardingAction.DisplayNameChanged("MyChoice"))
        runCurrent()

        assertEquals("MyChoice", vm.state.displayName)
        assertTrue(vm.state.userEditedName)
    }

    @Test
    fun regenerateDisplayName_replacesValue_andMarksUserEdited() = runUnitTest {
        val vm = newVm()
        val before = vm.state.displayName

        vm.takeAction(OnboardingAction.RegenerateDisplayName)
        runCurrent()

        assertTrue(vm.state.userEditedName)
        assertTrue(vm.state.displayName.isNotBlank())
        assertNotNull(before)
    }

    @Test
    fun selectAvatar_storesEmojiAndBackground() = runUnitTest {
        val vm = newVm()

        vm.takeAction(OnboardingAction.SelectAvatar("🐯", "#a18bff"))
        runCurrent()

        assertEquals("🐯", vm.state.selectedEmoji)
        assertEquals("#a18bff", vm.state.selectedBackgroundColor)
    }

    @Test
    fun initialState_autoSelectsAStarterAvatar_pairedEmojiAndColor() = runUnitTest {
        // The user should never land on the picker with a blank identity — one
        // starter avatar is pre-selected (at random) so finishing without
        // touching the grid still saves a real avatar. The chosen emoji and its
        // background color must be a genuine pair from the starter pack.
        val vm = newVm()

        val emoji = vm.state.selectedEmoji
        val color = vm.state.selectedBackgroundColor
        assertNotNull(emoji, "an avatar emoji must be pre-selected")
        assertTrue(
            OnboardingViewModel.STARTER_PACK.any {
                it.emoji == emoji && it.backgroundColorHex == color
            },
            "pre-selected emoji+color must be a real paired option from the starter pack",
        )
    }

    @Test
    fun continueFromPickIdentity_guest_startsBackgroundCreation_advances_andLocksBack() = runUnitTest {
        val auth = FakeAuthRepository() // Unauthenticated → guest path
        val creator = FakeGuestAccountCreator()
        val vm = newVm(auth = auth, creator = creator)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.DisplayNameChanged("Picked"))
        vm.takeAction(OnboardingAction.SelectAvatar("🦊", "#ff6b35"))
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        assertEquals(OnboardingStep.HowItWorks, vm.state.step)
        assertTrue(vm.state.creationStarted)
        assertEquals(1, creator.startCalls)
        assertEquals(
            PendingIdentity(displayName = "Picked", avatarEmoji = "🦊", avatarBackgroundColor = "#ff6b35"),
            creator.lastIdentity,
        )
    }

    @Test
    fun continueFromPickIdentity_authenticated_patchesProfile_andSurfacesTakenName() = runUnitTest {
        // A real account already exists (e.g. OAuth claimed): patch directly,
        // surfacing a taken name on the field.
        val auth = FakeAuthRepository(initialAuthState = sampleAuthenticated)
        val profile = FakeProfileRepository(
            initial = Profile.Fallback(id = "f"),
            updateOutcome = UpdateProfileOutcome.DisplayNameTaken,
        )
        val creator = FakeGuestAccountCreator()
        val vm = newVm(auth = auth, profile = profile, creator = creator)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.DisplayNameChanged("Taken"))
        vm.takeAction(OnboardingAction.SelectAvatar("🦊", "#ff6b35"))
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        assertEquals(OnboardingStep.HowItWorks, vm.state.step)
        assertEquals(0, creator.startCalls, "authed path must not create a guest account")
        assertEquals("Taken" to ("🦊" to "#ff6b35"), profile.lastUpdateArgs)
        assertEquals(OnboardingSaveError.DisplayNameTaken, vm.state.saveError)
    }

    @Test
    fun continueFromHowItWorks_advancesToStarterGrant_andRevealsBalance() = runUnitTest {
        val cache = FakeAppCache()
        val chips = FakeChipsRepository(initial = 10_500L)
        val vm = newVm(cache = cache, chips = chips)
        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        vm.takeAction(OnboardingAction.ContinueFromHowItWorks)
        runCurrent()

        assertEquals(OnboardingStep.StarterGrant, vm.state.step)
        assertEquals(10_500L, vm.state.revealedChips)
        assertFalse(vm.state.grantRevealTimedOut)
        // Showing the number records that we did, so the Home dialog won't repeat it.
        assertTrue(cache.get().didSeeInitialGrantInOnboarding)
    }

    @Test
    fun starterGrant_offline_noBalanceNoConfig_showsNoNumber_andDoesNotMarkSeen() = runUnitTest {
        // No live balance and no config amount (empty config) → "reconnect"
        // copy, and we did NOT show a number, so the Home dialog can still
        // reveal it once the wallet syncs.
        val cache = FakeAppCache()
        val chips = FakeChipsRepository(initial = null)
        val vm = newVm(cache = cache, chips = chips)
        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        vm.takeAction(OnboardingAction.ContinueFromHowItWorks)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(OnboardingStep.StarterGrant, vm.state.step)
        assertNull(vm.state.revealedChips)
        assertTrue(vm.state.grantRevealTimedOut)
        assertFalse(cache.get().didSeeInitialGrantInOnboarding)
    }

    @Test
    fun back_isBlocked_onceCreationStarted() = runUnitTest {
        val vm = newVm()
        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()
        assertEquals(OnboardingStep.HowItWorks, vm.state.step)

        vm.takeAction(OnboardingAction.Back)
        runCurrent()

        // Back is a no-op — the account is forming, no return to landing.
        assertEquals(OnboardingStep.HowItWorks, vm.state.step)
    }

    @Test
    fun back_fromPickIdentity_returnsToWelcome_beforeCreation() = runUnitTest {
        val vm = newVm()
        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        assertEquals(OnboardingStep.PickIdentity, vm.state.step)

        vm.takeAction(OnboardingAction.Back)
        runCurrent()

        assertEquals(OnboardingStep.Welcome, vm.state.step)
    }

    @Test
    fun back_fromWelcome_isNoOp() = runUnitTest {
        val vm = newVm()
        assertEquals(OnboardingStep.Welcome, vm.state.step)

        vm.takeAction(OnboardingAction.Back)
        runCurrent()

        assertEquals(OnboardingStep.Welcome, vm.state.step)
    }

    @Test
    fun finish_noCreation_marksOnboarded_andNavigatesHome() = runUnitTest {
        val cache = FakeAppCache()
        val vm = newVm(cache = cache)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.Finish)
        runCurrent()

        assertTrue(cache.get().hasUserOnboarded)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun finish_afterGuestCreationSucceeds_navigatesHome_notDegraded() = runUnitTest {
        val cache = FakeAppCache()
        val creator = FakeGuestAccountCreator(failCreation = false)
        val vm = newVm(cache = cache, creator = creator)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()
        vm.takeAction(OnboardingAction.Finish)
        runCurrent()

        assertTrue(cache.get().hasUserOnboarded)
        assertFalse(vm.state.creationFailed)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun finish_afterGuestCreationFails_stillNavigatesHome_flaggedDegraded() = runUnitTest {
        val cache = FakeAppCache()
        val creator = FakeGuestAccountCreator(failCreation = true)
        val vm = newVm(cache = cache, creator = creator)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()
        vm.takeAction(OnboardingAction.Finish)
        runCurrent()

        assertTrue(cache.get().hasUserOnboarded)
        assertTrue(vm.state.creationFailed)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun finish_guestCreationNeverTerminates_stillNavigatesHome_afterTimeout() = runUnitTest {
        // The principle: onboarding must NEVER trap the user on a backend call.
        // If guest creation is wedged/offline and never reaches a terminal state,
        // "Take a seat" must still complete onboarding after the bounded wait —
        // the creator keeps retrying in the background.
        val cache = FakeAppCache()
        val creator = FakeGuestAccountCreator(hangInProgress = true)
        val vm = newVm(cache = cache, creator = creator)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()
        vm.takeAction(OnboardingAction.Finish)
        runCurrent()
        // Creation never terminates — only the timeout lets us through.
        advanceTimeBy(6_000)
        runCurrent()

        assertTrue(cache.get().hasUserOnboarded, "onboarding completes even when creation never terminates")
        assertTrue(vm.state.creationFailed, "degraded flag set when the wait times out")
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun signInWithOAuth_providerNotEnabled_surfacesOAuthProviderNotEnabled() = runUnitTest {
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.ProviderNotEnabled)
        val vm = newVm(auth = auth)

        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google))
        runCurrent()

        assertEquals(OnboardingAuthError.OAuthProviderNotEnabled, vm.state.authError)
    }

    @Test
    fun signInWithOAuth_networkError_surfacesOAuthNetworkError() = runUnitTest {
        val auth = FakeAuthRepository(
            oauthSignInOutcome = SignInOutcome.NetworkError(RuntimeException("offline")),
        )
        val vm = newVm(auth = auth)

        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Apple))
        runCurrent()

        assertEquals(OnboardingAuthError.OAuthNetworkError, vm.state.authError)
    }

    @Test
    fun signInWithOAuth_returningAccount_marksOnboardedAndNavigatesHome() = runUnitTest {
        // isNewAccount=false (the FakeProfileRepository default) → the account
        // already existed, so it's a returning sign-in: skip straight to Home.
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.Success)
        val vm = newVm(cache = cache, auth = auth)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Apple))
        runCurrent()

        assertEquals(OAuthProvider.Apple, auth.lastOAuthProvider)
        assertTrue(cache.get().hasUserOnboarded)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
        assertNull(vm.state.oauthInFlight)
    }

    @Test
    fun signInWithOAuth_brandNewAccount_runsThroughOnboarding_notStraightToHome() = runUnitTest {
        // The server reports isNewAccount=true for a freshly-created account →
        // brand-new. It should enter PickIdentity (and finish the grant reveal)
        // rather than landing cold on Home.
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.Success)
        val profile = FakeProfileRepository().apply { isNewAccount = true }
        val vm = newVm(cache = cache, auth = auth, profile = profile)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google))
        runCurrent()

        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertTrue(vm.state.identityClaimed)
        assertFalse(cache.get().hasUserOnboarded, "brand-new sign-up isn't onboarded until they finish")
        assertTrue(received.isEmpty(), "must not navigate Home from the brand-new path")
        assertNull(vm.state.oauthInFlight)
        // Legal consent is still stamped — proceeding off Welcome is acceptance.
        assertEquals(LegalUrls.LEGAL_VERSION, cache.get().acceptedLegalVersion)
    }

    @Test
    fun continueAsGuest_recordsLegalConsent_versionAndTimestamp() = runUnitTest {
        val cache = FakeAppCache()
        val vm = newVm(cache = cache)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        assertEquals(LegalUrls.LEGAL_VERSION, cache.get().acceptedLegalVersion)
        assertEquals(FixedClock.instant.toEpochMilliseconds(), cache.get().legalConsentAcceptedAt)
    }

    @Test
    fun signInWithOAuth_success_recordsLegalConsent() = runUnitTest {
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.Success)
        val vm = newVm(cache = cache, auth = auth)

        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google))
        runCurrent()

        assertEquals(LegalUrls.LEGAL_VERSION, cache.get().acceptedLegalVersion)
        assertEquals(FixedClock.instant.toEpochMilliseconds(), cache.get().legalConsentAcceptedAt)
    }

    @Test
    fun signIn_returningUser_doesNotRecordConsent() = runUnitTest {
        val cache = FakeAppCache()
        val vm = newVm(cache = cache)

        vm.takeAction(OnboardingAction.SignIn)
        runCurrent()

        assertEquals(0, cache.get().acceptedLegalVersion)
        assertNull(cache.get().legalConsentAcceptedAt)
    }

    @Test
    fun signInWithOAuth_cancelled_silentlyClearsInFlightFlag() = runUnitTest {
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.Cancelled)
        val vm = newVm(cache = cache, auth = auth)

        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google))
        runCurrent()

        assertFalse(cache.get().hasUserOnboarded)
        assertNull(vm.state.oauthInFlight)
        assertNull(vm.state.authError)
    }

    @Test
    fun signIn_emitsNavigateToSignInEvent() = runUnitTest {
        val vm = newVm()
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.SignIn)
        runCurrent()

        assertEquals(OnboardingEvent.NavigateToSignIn, received.firstOrNull())
        assertEquals(OnboardingStep.Welcome, vm.state.step)
    }

    @Test
    fun back_isBlocked_afterOAuthIdentityClaimed() = runUnitTest {
        // A brand-new OAuth account enters PickIdentity with identityClaimed —
        // the Welcome sign-in options no longer apply, so back must be a no-op.
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.Success)
        val profile = FakeProfileRepository().apply { isNewAccount = true }
        val vm = newVm(auth = auth, profile = profile)
        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google))
        runCurrent()
        assertEquals(OnboardingStep.PickIdentity, vm.state.step)

        vm.takeAction(OnboardingAction.Back)
        runCurrent()

        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
    }

    @Test
    fun appleSignIn_asAnonymousGuest_linkSuccess_entersPickIdentity_claimed() = runUnitTest {
        // Brand-new Apple identity gets LINKED to the current anonymous guest
        // (keeps chips/XP) and continues onboarding like a new signup.
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(
            linkAppleOutcome = LinkIdentityOutcome.Success,
            initialAuthState = sampleAnonymousGuest,
        )
        val vm = newVm(
            cache = cache,
            auth = auth,
            appleCoordinator = FakeAppleSignInCoordinator(credential = sampleAppleCredential),
        )

        vm.takeAction(OnboardingAction.SignInWithApple)
        runCurrent()

        assertEquals(1, auth.linkAppleCalls, "anonymous guest must take the link path")
        assertEquals(0, auth.appleSignInCalls)
        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertTrue(vm.state.identityClaimed)
        assertNull(vm.state.oauthInFlight)
        assertEquals(LegalUrls.LEGAL_VERSION, cache.get().acceptedLegalVersion)
    }

    @Test
    fun appleSignIn_identityOnAnotherAccount_signsIn_andNavigatesHome() = runUnitTest {
        // The Apple identity already belongs to an existing account: the link
        // is rejected, so we switch sessions and skip onboarding entirely.
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(
            linkAppleOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            appleSignInOutcome = SignInOutcome.Success,
            initialAuthState = sampleAnonymousGuest,
        )
        val vm = newVm(
            cache = cache,
            auth = auth,
            appleCoordinator = FakeAppleSignInCoordinator(credential = sampleAppleCredential),
        )
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.SignInWithApple)
        runCurrent()

        assertEquals(1, auth.appleSignInCalls, "rejected link must fall back to sign-in")
        assertTrue(cache.get().hasUserOnboarded)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
        assertNull(vm.state.oauthInFlight)
    }

    @Test
    fun appleSignIn_noAnonSession_brandNewAccount_runsThroughOnboarding() = runUnitTest {
        // Regression (bug: delete account -> Continue with Apple skipped onboarding).
        // After deletion there's NO anonymous session, so the link path is skipped
        // and we sign in. Supabase mints a NET-NEW account (server isNewAccount=true).
        // It must run through onboarding (PickIdentity + grant reveal), not land Home.
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(
            appleSignInOutcome = SignInOutcome.Success,
            initialAuthState = AuthState.Unauthenticated(),
        )
        val profile = FakeProfileRepository().apply { isNewAccount = true }
        val vm = newVm(
            cache = cache,
            auth = auth,
            profile = profile,
            appleCoordinator = FakeAppleSignInCoordinator(credential = sampleAppleCredential),
        )
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.SignInWithApple)
        runCurrent()

        assertEquals(0, auth.linkAppleCalls, "no anonymous guest to link onto")
        assertEquals(1, auth.appleSignInCalls)
        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertTrue(vm.state.identityClaimed)
        assertFalse(cache.get().hasUserOnboarded, "net-new account isn't onboarded until they finish")
        assertTrue(received.isEmpty(), "must not navigate Home from the net-new Apple path")
        assertNull(vm.state.oauthInFlight)
    }

    @Test
    fun appleSignIn_noAnonSession_existingAccount_navigatesHome() = runUnitTest {
        // No anonymous session, but the Apple sign-in lands on a pre-existing
        // account (isNewAccount=false, the default) → skip straight to Home.
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(
            appleSignInOutcome = SignInOutcome.Success,
            initialAuthState = AuthState.Unauthenticated(),
        )
        val vm = newVm(
            cache = cache,
            auth = auth,
            appleCoordinator = FakeAppleSignInCoordinator(credential = sampleAppleCredential),
        )
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.SignInWithApple)
        runCurrent()

        assertEquals(1, auth.appleSignInCalls)
        assertTrue(cache.get().hasUserOnboarded)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
        assertNull(vm.state.oauthInFlight)
    }

    @Test
    fun appleSignIn_sheetDismissed_isQuietNoOp() = runUnitTest {
        // (null, null) from the coordinator = the user closed the sheet; no
        // error banner, no in-flight spinner left behind.
        val vm = newVm(appleCoordinator = FakeAppleSignInCoordinator(credential = null))

        vm.takeAction(OnboardingAction.SignInWithApple)
        runCurrent()

        assertNull(vm.state.oauthInFlight)
        assertNull(vm.state.authError)
        assertEquals(OnboardingStep.Welcome, vm.state.step)
    }

    @Test
    fun appleSignIn_nativeFailure_surfacesOAuthFailed() = runUnitTest {
        val vm = newVm(
            appleCoordinator = FakeAppleSignInCoordinator(errorMessage = "ASAuthorization error"),
        )

        vm.takeAction(OnboardingAction.SignInWithApple)
        runCurrent()

        assertEquals(OnboardingAuthError.OAuthFailed, vm.state.authError)
        assertNull(vm.state.oauthInFlight)
    }

    // ---------- AUTH-31: abandonment settles at re-entry, not at VM clear ----------

    @Test
    fun backingOutOnWelcome_thenReturningAndCompleting_emitsNoAbandonment() = runUnitTest {
        // The prod session the case file reconstructed: back out of Welcome,
        // relaunch seconds later, finish 48s in. The funnel logged two
        // abandonments for a user who completed.
        val cache = FakeAppCache()
        val clock = MutableClock()
        val tree = CapturingLogTree()
        KLog.plant(tree)
        try {
            newVm(cache = cache, clock = clock)
            runCurrent()
            assertEquals("welcome", cache.get().onboardingAttempt?.step, "landing on Welcome opens an attempt")

            // Back out (app exits, VM clears), relaunch 12s later.
            clock.advanceBy(12.seconds)
            val relaunched = newVm(cache = cache, clock = clock)
            runCurrent()

            relaunched.takeAction(OnboardingAction.Finish)
            runCurrent()

            assertEquals(
                emptyList(),
                tree.eventNames().filter { it == "onboarding.abandoned" },
                "a user who came back and finished is not an abandonment",
            )
            assertTrue(cache.get().hasUserOnboarded)
            assertNull(cache.get().onboardingAttempt, "completing closes the attempt")
        } finally {
            KLog.uproot(tree)
        }
    }

    @Test
    fun attemptOlderThanTheWindow_reportsOneAbandonmentOnNextLaunch() = runUnitTest {
        val clock = MutableClock()
        val cache = FakeAppCache(
            initial = AppData(
                onboardingAttempt = OnboardingAttempt(
                    step = "pick_identity",
                    startedAtEpochMs = clock.now().toEpochMilliseconds(),
                ),
            ),
        )
        val tree = CapturingLogTree()
        KLog.plant(tree)
        try {
            clock.advanceBy(OnboardingViewModel.ABANDON_SETTLE_AFTER + 1.seconds)
            newVm(cache = cache, clock = clock)
            runCurrent()

            assertEquals(
                listOf("onboarding.abandoned"),
                tree.eventNames().filter { it == "onboarding.abandoned" },
            )
            val extras = tree.eventExtras("onboarding.abandoned")
            assertEquals("pick_identity", extras["step"], "the step reported is where they stopped, not where they are now")
            assertEquals("stale", extras["reason"])
        } finally {
            KLog.uproot(tree)
        }
    }

    @Test
    fun aSettledAttempt_isNotReportedAgainOnTheLaunchAfter() = runUnitTest {
        val clock = MutableClock()
        val cache = FakeAppCache(
            initial = AppData(
                onboardingAttempt = OnboardingAttempt(
                    step = "welcome",
                    startedAtEpochMs = clock.now().toEpochMilliseconds(),
                ),
            ),
        )
        clock.advanceBy(OnboardingViewModel.ABANDON_SETTLE_AFTER + 1.seconds)
        newVm(cache = cache, clock = clock)
        runCurrent()

        val tree = CapturingLogTree()
        KLog.plant(tree)
        try {
            newVm(cache = cache, clock = clock)
            runCurrent()

            assertEquals(
                emptyList(),
                tree.eventNames().filter { it == "onboarding.abandoned" },
                "one event per abandoned attempt per install, however many times they relaunch",
            )
        } finally {
            KLog.uproot(tree)
        }
    }

    @Test
    fun anAttemptLeftBehindByAnOnboardedUser_isClearedWithoutReporting() = runUnitTest {
        // Residue: the flag landed but the marker didn't clear. They onboarded,
        // so there is nothing to report — just tidy up.
        val clock = MutableClock()
        val cache = FakeAppCache(
            initial = AppData(
                hasUserOnboarded = true,
                onboardingAttempt = OnboardingAttempt(
                    step = "welcome",
                    startedAtEpochMs = clock.now().toEpochMilliseconds(),
                ),
            ),
        )
        val tree = CapturingLogTree()
        KLog.plant(tree)
        try {
            clock.advanceBy(OnboardingViewModel.ABANDON_SETTLE_AFTER + 1.seconds)
            newVm(cache = cache, clock = clock)
            runCurrent()

            assertEquals(emptyList(), tree.eventNames().filter { it == "onboarding.abandoned" })
            assertNull(cache.get().onboardingAttempt)
        } finally {
            KLog.uproot(tree)
        }
    }

    @Test
    fun advancingThroughSteps_movesTheAttemptWithoutRestartingItsClock() = runUnitTest {
        val cache = FakeAppCache()
        val clock = MutableClock()
        val vm = newVm(cache = cache, clock = clock)
        runCurrent()
        val startedAt = cache.get().onboardingAttempt?.startedAtEpochMs

        clock.advanceBy(30.seconds)
        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        val attempt = cache.get().onboardingAttempt
        assertEquals("pick_identity", attempt?.step)
        assertEquals(
            startedAt,
            attempt?.startedAtEpochMs,
            "the window measures the whole run, so stepping forward must not reset it",
        )
    }

    private class CapturingLogTree : LogTree() {
        val entries = mutableListOf<LogEntry>()
        override fun log(entry: LogEntry): LogId? {
            entries += entry
            return null
        }

        fun eventNames(): List<String> =
            entries.mapNotNull { it.context.extras[EXTRA_APP_EVENT] as? String }

        fun eventExtras(name: String): Map<String, Any?> =
            entries.first { it.context.extras[EXTRA_APP_EVENT] == name }.context.extras
    }

    @Test
    fun aClockThatMovedBackwards_doesNotWedgeTheAttemptForever() = runUnitTest {
        // NTP or a timezone correction can land the wall clock behind where the
        // marker was written. A negative age is younger than any window, so the
        // attempt would never settle — and because logStepViewed preserves the
        // original startedAtEpochMs, it stays stuck until real time catches up.
        val clock = MutableClock()
        val cache = FakeAppCache(
            initial = AppData(
                onboardingAttempt = OnboardingAttempt(
                    step = "welcome",
                    startedAtEpochMs = clock.now().toEpochMilliseconds(),
                ),
            ),
        )
        val tree = CapturingLogTree()
        KLog.plant(tree)
        try {
            clock.advanceBy(-(30.days))
            newVm(cache = cache, clock = clock)
            runCurrent()

            // Landing on Welcome opens a fresh attempt, so the marker is back —
            // what matters is that it's a *new* one. Left wedged, logStepViewed
            // would have carried the old start time forward forever.
            assertEquals(
                clock.now().toEpochMilliseconds(),
                cache.get().onboardingAttempt?.startedAtEpochMs,
                "an age we can't trust gets dropped and restarted, not carried forever",
            )
            assertEquals(
                emptyList(),
                tree.eventNames().filter { it == "onboarding.abandoned" },
                "and it must not be reported either — we don't know what happened",
            )
        } finally {
            KLog.uproot(tree)
        }
    }

    @Test
    fun anAbsurdlyOldAttempt_isDroppedRatherThanReportedWithGarbageAge() = runUnitTest {
        // A device that cold-boots with an unsynced clock writes the marker near
        // the epoch, then NTP corrects. Reporting that as an abandonment puts an
        // age of decades into the funnel.
        val clock = MutableClock()
        val cache = FakeAppCache(
            initial = AppData(
                onboardingAttempt = OnboardingAttempt(step = "welcome", startedAtEpochMs = 0L),
            ),
        )
        val tree = CapturingLogTree()
        KLog.plant(tree)
        try {
            newVm(cache = cache, clock = clock)
            runCurrent()

            assertEquals(
                emptyList(),
                tree.eventNames().filter { it == "onboarding.abandoned" },
                "a decades-old age is a broken clock, not a user who walked away",
            )
            assertEquals(
                clock.now().toEpochMilliseconds(),
                cache.get().onboardingAttempt?.startedAtEpochMs,
                "the unusable marker is replaced by one written against a clock we trust",
            )
        } finally {
            KLog.uproot(tree)
        }
    }

    private class MutableClock : kotlin.time.Clock {
        private var current = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000L)
        override fun now() = current
        fun advanceBy(duration: kotlin.time.Duration) { current += duration }
    }

    // ---------- Test scaffolding ----------

    private fun newVm(
        cache: FakeAppCache = FakeAppCache(),
        auth: FakeAuthRepository = FakeAuthRepository(),
        profile: FakeProfileRepository = FakeProfileRepository(),
        chips: FakeChipsRepository = FakeChipsRepository(),
        creator: FakeGuestAccountCreator = FakeGuestAccountCreator(),
        appleCoordinator: FakeAppleSignInCoordinator = FakeAppleSignInCoordinator(),
        clock: kotlin.time.Clock = FixedClock,
    ): OnboardingViewModel {
        val config = EmptyAppConfigMap()
        return OnboardingViewModel(
            appCache = cache,
            authRepository = auth,
            profileRepository = profile,
            chipsRepository = chips,
            authOutcomeClassifier = FakeAuthOutcomeClassifier(profile),
            guestAccountCreator = creator,
            appleSignInCoordinator = appleCoordinator,
            onboardingStarterGrant = OnboardingStarterGrant(config),
            clock = clock,
            onboardingSuggestedName = OnboardingSuggestedName(config),
            googleSignInEnabled = GoogleSignInEnabled(config),
            appleSignInEnabled = AppleSignInEnabled(config),
        )
    }

    private object FixedClock : kotlin.time.Clock {
        val instant = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000L)
        override fun now() = instant
    }
}

internal class FakeGuestAccountCreator(
    private val failCreation: Boolean = false,
    private val failureCause: Throwable? = null,
    // Models a wedged / offline creation that never reaches a terminal state —
    // start() parks in InProgress and awaitTerminal() never returns. The finish
    // path must time out and proceed Home anyway rather than trap the user.
    private val hangInProgress: Boolean = false,
) : GuestAccountCreator {
    private val _state = MutableStateFlow<AccountCreationState>(AccountCreationState.Idle)
    override val state: StateFlow<AccountCreationState> = _state

    var startCalls: Int = 0
        private set
    var lastIdentity: PendingIdentity? = null
        private set

    override fun start(identity: PendingIdentity) {
        startCalls++
        lastIdentity = identity
        _state.value = when {
            hangInProgress -> AccountCreationState.InProgress
            failCreation -> AccountCreationState.Failed(identity, failureCause)
            else -> AccountCreationState.Succeeded
        }
    }

    override suspend fun awaitTerminal(): AccountCreationState =
        state.first { it is AccountCreationState.Succeeded || it is AccountCreationState.Failed }

    override fun retry() {
        lastIdentity?.let { start(it) }
    }

    override suspend fun ensureSession(fallbackIdentity: PendingIdentity): AccountCreationState {
        start(fallbackIdentity)
        return _state.value
    }
}

/**
 * Mirrors `DefaultAuthOutcomeClassifier`: reads the brand-new-account signal off
 * the [FakeProfileRepository] so tests keep driving it via `profile.isNewAccount`.
 */
internal class FakeAuthOutcomeClassifier(
    private val profile: FakeProfileRepository,
) : AuthOutcomeClassifier {
    override suspend fun classify(wasLink: Boolean): AuthOutcome = when {
        wasLink -> AuthOutcome.Linked
        profile.isNewAccount -> AuthOutcome.SignedUp
        else -> AuthOutcome.SignedIn
    }
}

internal class FakeProfileRepository(
    initial: Profile = Profile.Fallback(id = "anon"),
    private val updateOutcome: UpdateProfileOutcome = UpdateProfileOutcome.Success(
        profile = Profile.Authenticated(
            id = "11111111-1111-1111-1111-111111111111",
            displayName = "Default",
            avatarEmoji = "🦊",
            avatarBackgroundColor = null,
            email = null,
            isAnonymous = true,
            createdAt = Instant.fromEpochSeconds(0),
        ),
    ),
) : ProfileRepository {
    private val flow = MutableStateFlow(initial)
    var lastUpdateArgs: Pair<String?, Pair<String?, String?>>? = null
        private set

    /** Drives [resolveIsNewAccount] — the authoritative brand-new-account signal
     *  the VM uses to classify SIGN-UP vs SIGN-IN (server `/v1/me` isNewAccount). */
    var isNewAccount: Boolean = false

    suspend fun emit(next: Profile) { flow.emit(next) }

    override suspend fun current(): Profile = flow.value
    override fun observe(): Flow<Profile> = flow
    override suspend fun resolveIsNewAccount(): Boolean = isNewAccount

    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome {
        lastUpdateArgs = displayName to (avatarEmoji to avatarBackgroundColor)
        return updateOutcome
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        AvatarPackOutcome.Success(packs = emptyList(), palette = emptyList())
}

internal class FakeChipsRepository(initial: Long? = null) : ChipsRepository {
    val balance = MutableStateFlow(initial)
    var syncCalls: Int = 0
        private set

    override fun observeBalance(): Flow<Long?> = balance
    override suspend fun getBalance(): Long? = balance.value
    override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
        balance.value = (balance.value ?: 0L) + amount
    }
    override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
        balance.value = (balance.value ?: 0L) - amount
    }
    override suspend fun setBalance(authoritativeBalance: Long) {
        balance.value = authoritativeBalance
    }
    override suspend fun deleteAll() { balance.value = null }
    override suspend fun sync(): Result<Unit> {
        syncCalls++
        return Result.success(Unit)
    }
}
