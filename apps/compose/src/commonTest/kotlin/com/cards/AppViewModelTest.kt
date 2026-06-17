package com.dangerfield.cards

import app.cash.turbine.test
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.config.EnsureAppConfigLoaded
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Pins [AppViewModel]'s two-stage boot-gate logic. The VM decides:
 *   - which Route to start at (Home vs Onboarding) based on
 *     `AppData.hasUserOnboarded`,
 *   - when the platform splash can dismiss (`isReady`) — as soon as the
 *     start destination is resolved, regardless of user kind, so it hands
 *     off to the Compose boot gate, and
 *   - when the Compose boot gate releases to the nav graph
 *     (`isBootComplete`): once app-config has resolved (or timed out) and —
 *     for onboarded users — the first [ProfileRepository.observe] emission
 *     has landed (so home renders authoritative data, not a stale flash).
 *
 * Regressions here are a P0 launch-stall or a wrong start destination —
 * the kind that's easy to spot in QA but easier to catch in test.
 */
class AppViewModelTest : CoroutineTest() {

    @Test
    fun firstLaunchUser_landsOnOnboardingRoute_andIsReadyImmediately() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val profile = FakeProfileRepository()
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()), instantConfig())

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
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()), instantConfig())

        vm.startDestination.test {
            assertTrue(awaitItem() is HomeRoute)
        }
    }

    @Test
    fun isReady_flipsImmediately_evenForOnboardedUserAwaitingProfile() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        // Profile never emits — isReady must still flip so the platform
        // splash hands off to the Compose boot gate.
        val profile = ManualProfileRepository()
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()), instantConfig())

        vm.isReady.test {
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun onboardedUser_isBootComplete_onlyAfterProfileEmits() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        // Cold flow that we manually advance — the VM should not flip
        // isBootComplete until our emit() lands.
        val profile = ManualProfileRepository()
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()), instantConfig())

        vm.isBootComplete.test {
            assertEquals(false, awaitItem())
            // Sanity: profile hasn't emitted, boot is still gated.
            expectNoEvents()

            profile.emit(authenticated("user-1", "Elijah"))

            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun firstLaunchUser_isBootComplete_onlyAfterConfigResolves() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val profile = FakeProfileRepository()
        // First-launch users don't wait on profile, so app-config is the
        // sole gate on the boot-complete signal.
        val config = ManualEnsureConfig()
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()), config)

        vm.isBootComplete.test {
            assertEquals(false, awaitItem())
            expectNoEvents()

            config.complete()

            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun isBootComplete_flips_whenConfigNeverResolves_viaTimeout() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val profile = FakeProfileRepository()
        // Config never completes; the boot gate must still release once the
        // hard-cap timeout elapses (virtual time) so we never strand boot.
        val vm = AppViewModel(cache, profile, FakeAuthRepository(idleAuth()), ManualEnsureConfig())

        vm.isBootComplete.test {
            assertEquals(false, awaitItem())
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun sessionExpired_setsHasOnboardedFalse_andEmitsEvent_carryingAnonymity() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        val profile = FakeProfileRepository(initial = authenticated("guest-1", "Guest"))
        val auth = FakeAuthRepository(idleAuth())
        val vm = AppViewModel(cache, profile, auth, instantConfig())

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
        val vm = AppViewModel(cache, profile, auth, instantConfig())

        vm.sessionExpired.test {
            auth.emit(AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.None))
            expectNoEvents()
        }
        assertTrue(cache.get().hasUserOnboarded, "a benign sign-out must not touch the onboarded flag here")
    }

    private fun idleAuth(): AuthState =
        AuthState.Authenticated(userId = "u", isAnonymous = false, email = null)

    /** Config that resolves the instant it's awaited. */
    private fun instantConfig() = EnsureAppConfigLoaded { Catching { } }

    /** Config whose resolution is gated until [complete] is called. */
    private class ManualEnsureConfig : EnsureAppConfigLoaded {
        private val gate = CompletableDeferred<Unit>()
        override suspend fun invoke(): Catching<Unit> = Catching { gate.await() }
        fun complete() { gate.complete(Unit) }
    }

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
