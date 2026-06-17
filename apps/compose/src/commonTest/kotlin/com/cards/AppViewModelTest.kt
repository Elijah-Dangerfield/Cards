package com.dangerfield.cards

import app.cash.turbine.test
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Pins [AppViewModel]'s splash-gate logic. The VM decides:
 *   - which Route to start at (Home vs Onboarding) based on
 *     `AppData.hasUserOnboarded`, and
 *   - when the splash screen can dismiss (`isReady`):
 *       * first-launch users → as soon as start destination is resolved
 *         (no profile to wait on),
 *       * onboarded users → after the first [ProfileRepository.observe]
 *         emission so the home renders with authoritative data on frame
 *         one instead of flashing stale cache.
 *
 * Regressions here are a P0 launch-stall or a wrong start destination —
 * the kind that's easy to spot in QA but easier to catch in test.
 */
class AppViewModelTest : CoroutineTest() {

    @Test
    fun firstLaunchUser_landsOnOnboardingRoute_andIsReadyImmediately() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val profile = FakeProfileRepository()
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()))

        vm.startDestination.test {
            assertTrue(awaitItem() is OnboardingRoute)
        }
        vm.isReady.test {
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun onboardedUser_landsOnHomeRoute() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        val profile = FakeProfileRepository(initial = authenticated("user-1", "Elijah"))
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()))

        vm.startDestination.test {
            assertTrue(awaitItem() is HomeRoute)
        }
    }

    @Test
    fun onboardedUser_isReady_onlyAfterProfileEmits() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        // Cold flow that we manually advance — the VM should not flip
        // isReady until our emit() lands.
        val profile = ManualProfileRepository()
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()))

        vm.isReady.test {
            assertEquals(false, awaitItem())
            // Sanity: profile hasn't emitted, isReady is still false.
            expectNoEvents()

            profile.emit(authenticated("user-1", "Elijah"))

            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun sessionExpired_setsHasOnboardedFalse_andEmitsEvent_carryingAnonymity() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        val profile = FakeProfileRepository(initial = authenticated("guest-1", "Guest"))
        val auth = FakeAuthRepository(idleAuth())
        val vm = AppViewModel(cache, profile, auth)

        vm.sessionExpired.test {
            // The auth server rejected our (guest) session mid-run.
            auth.emit(
                AuthState.Unauthenticated(
                    reason = AuthState.Unauthenticated.Reason.SessionExpired,
                    wasAnonymous = true,
                ),
            )
            assertTrue(awaitItem().wasAnonymous, "the guest flag must reach the App for routing")
        }
        // Mirrors sign-out so a cold boot also lands on onboarding.
        assertFalse(cache.get().hasUserOnboarded, "session expiry resets the onboarded flag")
    }

    @Test
    fun benignUnauthenticated_doesNotBoot() = runUnitTest {
        // A plain Unauthenticated (offline / clean sign-out) must NOT fire the
        // session-expiry boot — only a server-confirmed rejection does.
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        val profile = FakeProfileRepository(initial = authenticated("u", "E"))
        val auth = FakeAuthRepository(idleAuth())
        val vm = AppViewModel(cache, profile, auth)

        vm.sessionExpired.test {
            auth.emit(AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.None))
            expectNoEvents()
        }
        assertTrue(cache.get().hasUserOnboarded, "a benign sign-out must not touch the onboarded flag here")
    }

    private fun idleAuth(): AuthState =
        AuthState.Authenticated(userId = "u", isAnonymous = false, email = null)

    private fun authenticated(id: String, name: String): Profile.Authenticated =
        Profile.Authenticated(
            id = id,
            displayName = name,
            avatarEmoji = "🦊",
            avatarBackgroundColor = null,
            email = null,
            isAnonymous = false,
            createdAt = Clock.System.now(),
        )

    private class FakeAuthRepository(initial: AuthState) : AuthRepository {
        private val state = MutableStateFlow(initial)
        suspend fun emit(next: AuthState) { state.emit(next) }
        override suspend fun current(): AuthState = state.value
        override fun observe(): Flow<AuthState> = state
        override suspend fun retry(): AuthState = state.value
        override suspend fun signInWithEmail(email: String, password: String) = error("unused")
        override suspend fun signUpWithEmail(email: String, password: String) = error("unused")
        override suspend fun refreshSession() = error("unused")
        override suspend fun resendVerificationEmail(email: String) = error("unused")
        override suspend fun sendPasswordResetEmail(email: String) = error("unused")
        override suspend fun signOut() = error("unused")
        override suspend fun deleteAccount() = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider) = error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider) = error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String) = error("unused")
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class FakeProfileRepository(
        initial: Profile = Profile.Fallback(id = "anon"),
    ) : ProfileRepository {
        private val state = MutableStateFlow(initial)
        override suspend fun current(): Profile = state.value
        override fun observe(): Flow<Profile> = state
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
            featuredBadgeIds: List<String>?,
        ): UpdateProfileOutcome = error("not used by AppViewModel")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome =
            error("not used by AppViewModel")
    }

    private class ManualProfileRepository : ProfileRepository {
        private val state = MutableStateFlow<Profile?>(null)
        override suspend fun current(): Profile = state.value ?: error("not emitted yet")
        override fun observe(): Flow<Profile> = kotlinx.coroutines.flow.flow {
            state.collect { value -> if (value != null) emit(value) }
        }
        suspend fun emit(p: Profile) { state.emit(p) }
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
            featuredBadgeIds: List<String>?,
        ): UpdateProfileOutcome = error("not used by AppViewModel")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome =
            error("not used by AppViewModel")
    }
}
