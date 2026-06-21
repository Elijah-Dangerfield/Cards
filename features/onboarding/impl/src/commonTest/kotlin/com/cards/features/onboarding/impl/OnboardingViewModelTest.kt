package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.OnboardingSuggestedName
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
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
 *  - Back is blocked once creation has started.
 *  - SignInWithOAuth: success → onboarded + Home; cancel/failure handled.
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
    fun signInWithOAuth_success_marksOnboardedAndNavigatesHome() = runUnitTest {
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
    fun dismissError_clearsOAuthError() = runUnitTest {
        val auth = FakeAuthRepository(oauthSignInOutcome = SignInOutcome.ProviderNotEnabled)
        val vm = newVm(auth = auth)
        vm.takeAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google))
        runCurrent()
        assertNotNull(vm.state.authError)

        vm.takeAction(OnboardingAction.DismissError)
        runCurrent()

        assertNull(vm.state.authError)
    }

    // ---------- Test scaffolding ----------

    private fun newVm(
        cache: FakeAppCache = FakeAppCache(),
        auth: FakeAuthRepository = FakeAuthRepository(),
        profile: FakeProfileRepository = FakeProfileRepository(),
        chips: FakeChipsRepository = FakeChipsRepository(),
        creator: FakeGuestAccountCreator = FakeGuestAccountCreator(),
    ): OnboardingViewModel {
        val config = EmptyAppConfigMap()
        return OnboardingViewModel(
            appCache = cache,
            authRepository = auth,
            profileRepository = profile,
            chipsRepository = chips,
            guestAccountCreator = creator,
            appleSignInCoordinator = NoopAppleSignInCoordinator,
            onboardingStarterGrant = OnboardingStarterGrant(config),
            onboardingSuggestedName = OnboardingSuggestedName(config),
            googleSignInEnabled = GoogleSignInEnabled(config),
            appleSignInEnabled = AppleSignInEnabled(config),
        )
    }

    /** No test here exercises the iOS-only Apple flow; the coordinator is a no-op. */
    private object NoopAppleSignInCoordinator :
        com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator {
        override fun requestCredential(
            onComplete: (com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential?, String?) -> Unit,
        ) = onComplete(null, null)
    }
}

internal class FakeGuestAccountCreator(
    private val failCreation: Boolean = false,
    private val failureCause: Throwable? = null,
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
        _state.value = if (failCreation) {
            AccountCreationState.Failed(identity, failureCause)
        } else {
            AccountCreationState.Succeeded
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

    suspend fun emit(next: Profile) { flow.emit(next) }

    override suspend fun current(): Profile = flow.value
    override fun observe(): Flow<Profile> = flow

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
    override val walletJustCreated = MutableStateFlow(false)
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
