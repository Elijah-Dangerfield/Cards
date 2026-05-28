package com.dangerfield.cards

import app.cash.turbine.test
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val vm = AppViewModel(cache, profile)

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
        val vm = AppViewModel(cache, profile)

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
        val vm = AppViewModel(cache, profile)

        vm.isReady.test {
            assertEquals(false, awaitItem())
            // Sanity: profile hasn't emitted, isReady is still false.
            expectNoEvents()

            profile.emit(authenticated("user-1", "Elijah"))

            assertEquals(true, awaitItem())
        }
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
        ): UpdateProfileOutcome = error("not used by AppViewModel")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome =
            error("not used by AppViewModel")
    }
}
