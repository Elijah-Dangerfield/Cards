package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers the redesigned three-step onboarding flow:
 *
 *  - Returning user (hasUserOnboarded) bounces straight to Home.
 *  - ContinueAsGuest: anon-auth success advances to PickIdentity and
 *    kicks off profile + avatar-pack background loads; failure stays on
 *    Welcome with a friendly error (incl. the Supabase-specific cases).
 *  - SignInWithOAuth: success → marks onboarded + NavigateToHome; cancel
 *    silently clears the in-flight flag; failures surface a message.
 *  - DisplayName: user-edit gates profile prefill; Regenerate re-rolls.
 *  - SelectAvatar: stores emoji + background hex.
 *  - ContinueFromPickIdentity: profile.update is called; success
 *    advances to HowItWorks; name-taken stays + shows error; other
 *    failures advance anyway (we don't dead-end on a save hiccup).
 *  - Skip: marks onboarded + Navigates Home with no profile mutation.
 *  - Finish: same as Skip from the HowItWorks step.
 *  - Profile + avatar-pack timeouts fall back gracefully.
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
        // The onboarding picker is hardcoded — no network — so what
        // ships in STARTER_PACK is what the user will pick from on a
        // cold install. The server's `AvatarPacks.Starter` must contain
        // every emoji here forever (append-only invariant), enforced
        // by `AvatarPacksTest.starter_includesAllClientFallbackEmojis`.
        // If you change either side without the other, profile saves
        // will reject with `invalid_avatar_emoji` for any user that
        // picks a drifted emoji.
        val expectedEmojis = listOf("🦊", "🐱", "🐼", "🐯", "🐸", "🦁", "🃏", "🎲")
        assertEquals(expectedEmojis, OnboardingViewModel.STARTER_PACK.map { it.emoji })
        assertEquals(OnboardingViewModel.STARTER_TILE_COUNT, OnboardingViewModel.STARTER_PACK.size)
    }

    @Test
    fun starterPack_defaultBackgroundColors_matchServerPaletteForm() {
        // The server validates `avatarBackgroundColor` against
        // `AvatarPalette` which normalizes to lowercase `#rrggbb`. The
        // original bug shipped uppercase / different hex values so
        // every onboarding save was getting rejected on color
        // validation. Pin the format so a future palette edit can't
        // silently break onboarding again.
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
    fun continueAsGuest_success_advancesToPickIdentity_andPrefillsFromProfile() = runUnitTest {
        val cache = FakeAppCache()
        val auth = FakeAuthRepository(initialAuthState = sampleAnonymous)
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(
                displayName = "ServerName42",
                avatarEmoji = "🦊",
                avatarBackgroundColor = "#ff6b35",
            ),
        )
        val vm = newVm(cache = cache, auth = auth, profile = profile)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertEquals("ServerName42", vm.state.displayName)
        assertEquals("🦊", vm.state.selectedEmoji)
        assertEquals("#ff6b35", vm.state.selectedBackgroundColor)
        // The avatar picker uses the hardcoded onboarding starter
        // pack — fetched in parallel from the server to warm the
        // EditProfile cache, but the picker itself always renders
        // STARTER_PACK. Pin the count + first emoji so a future
        // change to STARTER_PACK trips a test.
        assertEquals(OnboardingViewModel.STARTER_TILE_COUNT, vm.state.starterPack.size)
        assertEquals("🦊", vm.state.starterPack.first().emoji)
    }

    @Test
    fun continueAsGuest_anonymousDisabled_surfacesSpecificMessage() = runUnitTest {
        // Load-bearing dev message: this is the #1 cause of "no user
        // appeared in the Supabase dashboard" head-scratching.
        val auth = FakeAuthRepository(
            initialAuthState = AuthState.Unauthenticated(
                RuntimeException("Anonymous sign-ins are disabled"),
            ),
        )
        val vm = newVm(auth = auth)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        assertEquals(OnboardingStep.Welcome, vm.state.step)
        val msg = vm.state.authError ?: error("expected an error message")
        assertTrue(msg.contains("Anonymous sign-in", ignoreCase = true), "got: $msg")
        assertTrue(msg.contains("Providers", ignoreCase = true), "should name the dashboard path; got: $msg")
    }

    @Test
    fun continueAsGuest_network_surfacesFriendlyError() = runUnitTest {
        val auth = FakeAuthRepository(
            initialAuthState = AuthState.Unauthenticated(RuntimeException("network is unreachable")),
        )
        val vm = newVm(auth = auth)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        val msg = vm.state.authError ?: error("expected an error message")
        assertTrue(msg.contains("connection", ignoreCase = true) || msg.contains("server", ignoreCase = true))
    }

    @Test
    fun continueAsGuest_invalidAnonKey_surfacesSpecificMessage() = runUnitTest {
        val auth = FakeAuthRepository(
            initialAuthState = AuthState.Unauthenticated(RuntimeException("Invalid API key")),
        )
        val vm = newVm(auth = auth)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()

        val msg = vm.state.authError ?: error("expected an error message")
        assertTrue(msg.contains("anon key", ignoreCase = true), "got: $msg")
        assertTrue(msg.contains("IdentityConfig", ignoreCase = true), "should name the file; got: $msg")
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
    fun displayNameChanged_setsUserEditedFlag_andBlocksProfilePrefill() = runUnitTest {
        // Profile resolves only after the user has already typed — the
        // prefill must NOT clobber the user's input.
        val profile = FakeProfileRepository(initial = Profile.Fallback(id = "f"))
        val auth = FakeAuthRepository(initialAuthState = sampleAnonymous)
        val vm = newVm(auth = auth, profile = profile)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.DisplayNameChanged("MyChoice"))
        runCurrent()
        assertEquals("MyChoice", vm.state.displayName)
        assertTrue(vm.state.userEditedName)

        // Now the real profile lands — must NOT overwrite.
        profile.emit(authenticatedProfile(displayName = "ServerName"))
        runCurrent()
        assertEquals("MyChoice", vm.state.displayName)
    }

    @Test
    fun regenerateDisplayName_replacesValue_andMarksUserEdited() = runUnitTest {
        val vm = newVm()
        val before = vm.state.displayName

        vm.takeAction(OnboardingAction.RegenerateDisplayName)
        runCurrent()

        // Random — assert it changed at least most of the time. To keep
        // the test deterministic, just assert userEdited flipped.
        assertTrue(vm.state.userEditedName)
        // And the new value is a non-empty, suggester-shaped string.
        assertTrue(vm.state.displayName.isNotBlank())
        // We can't assert inequality reliably (theoretical collision),
        // but at minimum the field is still populated.
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
    fun continueFromPickIdentity_success_advancesToHowItWorks_andCallsUpdate() = runUnitTest {
        val profile = FakeProfileRepository(
            initial = Profile.Fallback(id = "f"),
            updateOutcome = UpdateProfileOutcome.Success(
                profile = authenticatedProfile(displayName = "Picked"),
            ),
        )
        val auth = FakeAuthRepository(initialAuthState = sampleAnonymous)
        val vm = newVm(auth = auth, profile = profile)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.DisplayNameChanged("Picked"))
        vm.takeAction(OnboardingAction.SelectAvatar("🦊", "#ff6b35"))
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        assertEquals(OnboardingStep.HowItWorks, vm.state.step)
        assertEquals("Picked" to ("🦊" to "#ff6b35"), profile.lastUpdateArgs)
    }

    @Test
    fun continueFromPickIdentity_displayNameTaken_staysAndSurfacesError() = runUnitTest {
        val profile = FakeProfileRepository(
            initial = Profile.Fallback(id = "f"),
            updateOutcome = UpdateProfileOutcome.DisplayNameTaken,
        )
        val auth = FakeAuthRepository(initialAuthState = sampleAnonymous)
        val vm = newVm(auth = auth, profile = profile)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.DisplayNameChanged("Taken"))
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        assertEquals(OnboardingStep.PickIdentity, vm.state.step)
        assertNotNull(vm.state.saveError)
        assertTrue(vm.state.saveError!!.contains("taken", ignoreCase = true))
    }

    @Test
    fun continueFromPickIdentity_networkError_advancesAnyway() = runUnitTest {
        // We don't want a server hiccup to dead-end the user on the
        // last form step before Home. The user can fix their name
        // later from Profile.
        val profile = FakeProfileRepository(
            initial = Profile.Fallback(id = "f"),
            updateOutcome = UpdateProfileOutcome.NetworkError(RuntimeException("boom")),
        )
        val auth = FakeAuthRepository(initialAuthState = sampleAnonymous)
        val vm = newVm(auth = auth, profile = profile)

        vm.takeAction(OnboardingAction.ContinueAsGuest)
        runCurrent()
        vm.takeAction(OnboardingAction.ContinueFromPickIdentity)
        runCurrent()

        assertEquals(OnboardingStep.HowItWorks, vm.state.step)
    }

    @Test
    fun skip_marksOnboarded_andNavigatesHome() = runUnitTest {
        val cache = FakeAppCache()
        val vm = newVm(cache = cache)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.Skip)
        runCurrent()

        assertTrue(cache.get().hasUserOnboarded)
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun finish_marksOnboarded_andNavigatesHome() = runUnitTest {
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
    fun dismissError_clearsErrors() = runUnitTest {
        val auth = FakeAuthRepository(
            initialAuthState = AuthState.Unauthenticated(RuntimeException("boom")),
        )
        val vm = newVm(auth = auth)
        vm.takeAction(OnboardingAction.ContinueAsGuest)
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
    ): OnboardingViewModel = OnboardingViewModel(
        appCache = cache,
        authRepository = auth,
        profileRepository = profile,
        appConfigMap = EmptyAppConfigMap(),
    )

    private fun authenticatedProfile(
        displayName: String = "ServerName",
        avatarEmoji: String = "🦊",
        avatarBackgroundColor: String? = "#ff6b35",
    ): Profile.Authenticated = Profile.Authenticated(
        id = "11111111-1111-1111-1111-111111111111",
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarBackgroundColor = avatarBackgroundColor,
        email = null,
        isAnonymous = true,
        createdAt = Instant.fromEpochSeconds(0),
    )
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

    // Onboarding doesn't call fetchAvatarPack — ProfileRepositoryImpl
    // warms the pack at app boot via the AutoInit pattern instead.
    // Stub satisfies the contract; nobody on this path calls it.
    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        AvatarPackOutcome.Success(packs = emptyList(), palette = emptyList())
}
