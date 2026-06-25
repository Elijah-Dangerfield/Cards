package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserScopedAppDataResetTest : CoroutineTest() {

    @Test
    fun clear_resetsAccountScopedFields_butKeepsDeviceSettings() = runUnitTest {
        val cache = FakeAppCache(
            initial = AppData(
                didSeeInitialGrantInOnboarding = true, // account-scoped → reset
                hasUserOnboarded = true,               // device-scoped → kept
                tutorialBannerDismissed = true,        // identity-scoped → reset (AUTH-6)
            ),
        )
        val reset = UserScopedAppDataReset(appCache = cache)

        reset.clear(previousUserId = "user-1")

        val after = cache.get()
        assertFalse(after.didSeeInitialGrantInOnboarding, "account-scoped grant flag must reset")
        assertTrue(after.hasUserOnboarded, "device-scoped onboarding flag must survive")
    }

    @Test
    fun clear_resetsTutorialBanner_soAFreshGuestIsReOfferedTheWalkthrough() = runUnitTest {
        // AUTH-6: sign-out -> continue-as-guest must show the "new here?" banner
        // again. The dismissal is identity-scoped, so a user change clears it.
        val cache = FakeAppCache(initial = AppData(tutorialBannerDismissed = true))
        val reset = UserScopedAppDataReset(appCache = cache)

        reset.clear(previousUserId = "user-1")

        assertFalse(
            cache.get().tutorialBannerDismissed,
            "the new-here banner must re-show for the next identity",
        )
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}
