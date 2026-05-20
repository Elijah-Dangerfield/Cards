package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.Identity
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the onboarding finish path:
 *  - On success: AppData.hasUserOnboarded flips true, NavigateToHome fires.
 *  - On generic failure: state.error is the generic friendly message, the
 *    onboarded flag stays false (no dead-end state).
 *  - On the anonymous-sign-ins-disabled error: state.error names the
 *    specific dashboard setting. This is the load-bearing case for V1 dev
 *    builds since it's the most common Supabase misconfig.
 *  - On invalid-anon-key error: state.error points at IdentityConfig.
 *  - On network error: state.error is the network message.
 *  - DismissError clears.
 */
class OnboardingViewModelTest : CoroutineTest() {

    @Test
    fun finish_success_persistsFlag_andNavigatesHome() = runUnitTest {
        val cache = FakeAppCache()
        val repo = FinishIdentityRepository(outcome = FinishOutcome.Success(SAMPLE_IDENTITY))
        val vm = OnboardingViewModel(cache, repo)
        val received = mutableListOf<OnboardingEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(OnboardingAction.Finish)

        assertEquals(1, repo.calls)
        assertFalse(vm.state.isInitializing)
        assertNull(vm.state.error)
        assertTrue(cache.get().hasUserOnboarded, "onboarded flag should flip true")
        assertEquals(OnboardingEvent.NavigateToHome, received.firstOrNull())
    }

    @Test
    fun finish_failure_leavesFlagFalse_andSurfacesError() = runUnitTest {
        val cache = FakeAppCache()
        val repo = FinishIdentityRepository(
            outcome = FinishOutcome.Throws(RuntimeException("network is unreachable")),
        )
        val vm = OnboardingViewModel(cache, repo)

        vm.takeAction(OnboardingAction.Finish)

        assertFalse(cache.get().hasUserOnboarded, "onboarded flag must stay false on failure")
        assertNotNull(vm.state.error)
        assertTrue(
            vm.state.error!!.contains("connection", ignoreCase = true) ||
                vm.state.error!!.contains("server", ignoreCase = true),
            "got: ${vm.state.error}",
        )
    }

    @Test
    fun finish_anonymousDisabled_surfacesSpecificMessage() = runUnitTest {
        // Pattern matches the Supabase response when the project's
        // "Allow anonymous sign-ins" toggle is off. This is the
        // #1 cause of "no users in Supabase dashboard" complaints
        // during dev — surfacing the dashboard path saves a real
        // round-trip.
        val cache = FakeAppCache()
        val repo = FinishIdentityRepository(
            outcome = FinishOutcome.Throws(
                RuntimeException("Anonymous sign-ins are disabled"),
            ),
        )
        val vm = OnboardingViewModel(cache, repo)

        vm.takeAction(OnboardingAction.Finish)

        val msg = vm.state.error ?: error("expected an error message")
        assertTrue(msg.contains("Anonymous sign-in", ignoreCase = true), "got: $msg")
        assertTrue(msg.contains("Providers", ignoreCase = true), "should name the dashboard path; got: $msg")
    }

    @Test
    fun finish_invalidAnonKey_surfacesSpecificMessage() = runUnitTest {
        val cache = FakeAppCache()
        val repo = FinishIdentityRepository(
            outcome = FinishOutcome.Throws(
                RuntimeException("Invalid API key"),
            ),
        )
        val vm = OnboardingViewModel(cache, repo)

        vm.takeAction(OnboardingAction.Finish)

        val msg = vm.state.error ?: error("expected an error message")
        assertTrue(msg.contains("anon key", ignoreCase = true), "got: $msg")
        assertTrue(msg.contains("IdentityConfig", ignoreCase = true), "should name the file; got: $msg")
    }

    @Test
    fun init_alreadyOnboarded_immediatelyNavigatesHome() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val repo = FinishIdentityRepository(outcome = FinishOutcome.Success(SAMPLE_IDENTITY))
        val received = mutableListOf<OnboardingEvent>()

        val vm = OnboardingViewModel(cache, repo)
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        assertEquals(
            OnboardingEvent.NavigateToHome,
            received.firstOrNull(),
            "returning user landing on OnboardingRoute should bounce to home",
        )
        assertEquals(0, repo.calls, "guard must not trigger identity init")
    }

    @Test
    fun init_notOnboarded_doesNotFireNavigateHome() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = false))
        val repo = FinishIdentityRepository(outcome = FinishOutcome.Success(SAMPLE_IDENTITY))
        val received = mutableListOf<OnboardingEvent>()

        val vm = OnboardingViewModel(cache, repo)
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        assertTrue(received.isEmpty(), "brand-new user must see the pager, got: $received")
    }

    @Test
    fun dismissError_clearsErrorState() = runUnitTest {
        val cache = FakeAppCache()
        val repo = FinishIdentityRepository(
            outcome = FinishOutcome.Throws(RuntimeException("boom")),
        )
        val vm = OnboardingViewModel(cache, repo)
        vm.takeAction(OnboardingAction.Finish)
        assertNotNull(vm.state.error)

        vm.takeAction(OnboardingAction.DismissError)

        assertNull(vm.state.error)
    }

    // ---------- Test scaffolding ----------

    /**
     * Minimal identity-repo fake that only stubs [ensureInitialized] — the
     * one method [OnboardingViewModel] calls. The shared
     * [FakeIdentityRepository] in `AuthViewModelFakes.kt` errors on this
     * method intentionally (auth viewmodels don't use it), so onboarding
     * tests need a dedicated fake.
     */
    private class FinishIdentityRepository(
        val outcome: FinishOutcome,
    ) : NoOpIdentityRepository() {
        var calls: Int = 0
            private set

        override suspend fun ensureInitialized(): Identity {
            calls += 1
            return when (outcome) {
                is FinishOutcome.Success -> outcome.identity
                is FinishOutcome.Throws -> throw outcome.cause
            }
        }
    }

    private sealed interface FinishOutcome {
        data class Success(val identity: Identity) : FinishOutcome
        data class Throws(val cause: Throwable) : FinishOutcome
    }

    companion object {
        private val SAMPLE_IDENTITY = Identity(
            userId = "11111111-1111-1111-1111-111111111111",
            displayName = "QuietAce72",
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            isAnonymous = true,
        )
    }
}
