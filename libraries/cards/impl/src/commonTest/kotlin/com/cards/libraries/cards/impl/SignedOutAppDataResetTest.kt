package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedOutAppDataResetTest : CoroutineTest() {

    @Test
    fun onSignedOut_resetsAccountScopedFields_butKeepsDeviceSettings() = runUnitTest {
        val cache = FakeAppCache(
            initial = AppData(
                didSeeInitialGrantInOnboarding = true, // account-scoped → reset
                hasUserOnboarded = true,               // device-scoped → kept
                tutorialBannerDismissed = true,        // device-scoped → kept
            ),
        )
        val reset = SignedOutAppDataReset(appCache = cache, appScope = AppCoroutineScope(dispatchers))

        reset.onSignedOut(AppEvent.SignedOut)
        advanceUntilIdle()

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
