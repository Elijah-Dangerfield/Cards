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
                tutorialBannerDismissed = true,        // device-scoped → kept
            ),
        )
        val reset = UserScopedAppDataReset(appCache = cache)

        reset.clear(previousUserId = "user-1")

        val after = cache.get()
        assertFalse(after.didSeeInitialGrantInOnboarding, "account-scoped grant flag must reset")
        assertTrue(after.hasUserOnboarded, "device-scoped onboarding flag must survive")
        assertTrue(after.tutorialBannerDismissed, "device-scoped UI flag must survive")
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}
