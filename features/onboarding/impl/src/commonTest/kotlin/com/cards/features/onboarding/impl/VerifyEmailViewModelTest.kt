package com.dangerfield.cards.features.onboarding.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins [VerifyEmailViewModel]'s outcome → banner mapping + side-effect
 * gates. Verify-email is where `hasUserOnboarded` actually flips —
 * sign-up alone doesn't qualify the user as onboarded; clicking the
 * email link does.
 *
 * What we pin:
 *  - IClickedTheLink → EmailConfirmed marks onboarded AND emits NavigateToHome
 *  - IClickedTheLink → StillPending stays put, sets StillPending banner
 *  - IClickedTheLink → SessionExpired emits NavigateBackToSignIn (no banner)
 *  - IClickedTheLink → NetworkError surfaces NetworkError banner
 *  - Resend → Sent surfaces ResendSent banner + clears isResending
 *  - Resend → RateLimited surfaces ResendRateLimited banner
 *  - DismissBanner clears the banner
 *  - Resend always passes the original email through to the repo
 */
class VerifyEmailViewModelTest : CoroutineTest() {

    private val sampleEmail = "ok@example.com"

    @Test
    fun iClickedTheLink_emailConfirmed_marksOnboarded_andNavigates() = runUnitTest {
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(
            refreshOutcome = RefreshOutcome.EmailConfirmed,
        )
        val vm = buildVm(identity = identity, appCache = cache)
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToHome>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
        assertEquals(1, identity.refreshCalls)
    }

    @Test
    fun iClickedTheLink_stillPending_setsStillPendingBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.StillPending),
        )
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner != VerifyEmailState.Banner.StillPending) last = awaitItem()
            assertEquals(false, last.isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun iClickedTheLink_sessionExpired_emitsNavigateBackToSignIn() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = false))
        val vm = buildVm(
            identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.SessionExpired),
            appCache = cache,
        )
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateBackToSignIn>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            false, cache.get().hasUserOnboarded,
            "session expired must not flip onboarding done",
        )
    }

    @Test
    fun iClickedTheLink_networkError_setsNetworkErrorBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                refreshOutcome = RefreshOutcome.NetworkError(RuntimeException("nope")),
            ),
        )
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner != VerifyEmailState.Banner.NetworkError) last = awaitItem()
            assertEquals(false, last.isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resend_sent_setsResendSentBanner_andClearsLoading() = runUnitTest {
        val identity = FakeAuthRepository(resendOutcome = ResendOutcome.Sent)
        val vm = buildVm(identity = identity)
        vm.takeAction(VerifyEmailAction.Resend)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner != VerifyEmailState.Banner.ResendSent) last = awaitItem()
            assertEquals(false, last.isResending)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.resendCalls)
        assertEquals(sampleEmail, identity.lastResendEmail)
    }

    @Test
    fun resend_rateLimited_setsRateLimitedBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                resendOutcome = ResendOutcome.RateLimited(retryAfterSeconds = 30),
            ),
        )
        vm.takeAction(VerifyEmailAction.Resend)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner != VerifyEmailState.Banner.ResendRateLimited) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun appResumed_emailConfirmed_marksOnboarded_andNavigates() = runUnitTest {
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.EmailConfirmed)
        val vm = buildVm(identity = identity, appCache = cache)
        vm.takeAction(VerifyEmailAction.AppResumed)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToHome>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
        assertEquals(1, identity.refreshCalls)
    }

    @Test
    fun appResumed_stillPending_isSilent_noBannerOrEvent() = runUnitTest {
        // Resume-driven refresh must not flash a banner — the user may have
        // backgrounded for unrelated reasons and we don't want a visual
        // every time they return to the app. Manual button still surfaces
        // the StillPending banner for diagnostic feedback.
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.StillPending)
        val vm = buildVm(identity = identity, appCache = cache)
        vm.takeAction(VerifyEmailAction.AppResumed)

        assertEquals(1, identity.refreshCalls)
        assertEquals(null, vm.stateFlow.value.banner)
        assertEquals(false, vm.stateFlow.value.isRefreshing)
        assertEquals(false, cache.get().hasUserOnboarded)
    }

    @Test
    fun appResumed_networkError_isSilent_noBannerOrEvent() = runUnitTest {
        val identity = FakeAuthRepository(
            refreshOutcome = RefreshOutcome.NetworkError(RuntimeException("offline")),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(VerifyEmailAction.AppResumed)

        assertEquals(1, identity.refreshCalls)
        assertEquals(null, vm.stateFlow.value.banner)
    }

    @Test
    fun appResumed_sessionExpired_emitsNavigateBackToSignIn() = runUnitTest {
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.SessionExpired)
        val vm = buildVm(identity = identity)
        vm.takeAction(VerifyEmailAction.AppResumed)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateBackToSignIn>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun dismissBanner_clearsBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.StillPending),
        )
        vm.takeAction(VerifyEmailAction.IClickedTheLink)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.takeAction(VerifyEmailAction.DismissBanner)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner != null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeAuthRepository = FakeAuthRepository(),
        appCache: FakeAppCache = FakeAppCache(),
    ): VerifyEmailViewModel = VerifyEmailViewModel(
        authRepository = identity,
        appCache = appCache,
        email = sampleEmail,
    )
}
