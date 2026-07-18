package com.dangerfield.cards.features.onboarding.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins [VerifyEmailViewModel]'s outcome → banner mapping + side-effect
 * gates. Verify-email is the branch point on confirmation:
 *  - a **returning** account (email was merely unconfirmed) already has a
 *    profile + wallet, so it marks onboarded and goes Home;
 *  - a **brand-new** signup still needs identity + starter grant, so it
 *    re-enters onboarding and does NOT mark onboarded (the flow's Finish
 *    step does). New-vs-returning is read off the authoritative server
 *    `/v1/me` `isNewAccount` signal via `ProfileRepository.resolveIsNewAccount`.
 *
 * What we pin:
 *  - IClickedTheLink → EmailConfirmed (returning) marks onboarded + NavigateToHome
 *  - IClickedTheLink → EmailConfirmed (brand-new) → NavigateToOnboarding, not onboarded
 *  - IClickedTheLink → StillPending stays put, sets StillPending banner
 *  - IClickedTheLink → SessionExpired (brand-new signup) stays put on StillPending;
 *    (guest link) emits NavigateBackToSignIn
 *  - IClickedTheLink → NetworkError surfaces NetworkError banner
 *  - AppResumed → SessionExpired (brand-new signup) is silent; (guest link) bounces
 *  - AppResumed → EmailConfirmed (brand-new) → NavigateToOnboarding
 *  - Resend → Sent surfaces ResendSent banner + clears isResending
 *  - Resend → RateLimited surfaces ResendRateLimited banner
 *  - Resend always passes the original email through to the repo
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyEmailViewModelTest : CoroutineTest() {

    private val sampleEmail = "ok@example.com"

    @Test
    fun iClickedTheLink_emailConfirmed_returning_marksOnboarded_andNavigatesHome() = runUnitTest {
        // isNewAccount stays false → the server reports a pre-existing account,
        // so it's a returning user whose email was merely unconfirmed: Home.
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(
            refreshOutcome = RefreshOutcome.EmailConfirmed,
        )
        val profile = FakeProfileRepository().apply { isNewAccount = false }
        val vm = buildVm(identity = identity, appCache = cache, profile = profile)
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToHome>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
        assertEquals(1, identity.refreshCalls)
    }

    @Test
    fun iClickedTheLink_emailConfirmed_brandNew_reentersOnboarding_notYetOnboarded() = runUnitTest {
        // The server reports isNewAccount=true for a freshly-created account →
        // this is a brand-new signup: re-enter onboarding for identity + grant,
        // and do NOT mark onboarded (the flow's Finish step owns that).
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.EmailConfirmed)
        val profile = FakeProfileRepository().apply { isNewAccount = true }
        val vm = buildVm(identity = identity, appCache = cache, profile = profile)
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToOnboarding>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            false, cache.get().hasUserOnboarded,
            "brand-new signup isn't onboarded until it finishes the flow",
        )
    }

    @Test
    fun iClickedTheLink_emailConfirmed_guestLink_marksOnboarded_showsAccountSaved() = runUnitTest {
        // An anonymous guest who linked an email keeps their existing account +
        // progress, so confirmation shows the "account saved" dialog — never
        // onboarding, never the silent Home bounce. isNewAccount=true here proves
        // the guest-link flag short-circuits the new-vs-returning check.
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.EmailConfirmed)
        val profile = FakeProfileRepository().apply { isNewAccount = true }
        val vm = buildVm(identity = identity, appCache = cache, profile = profile, guestLink = true)
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToAccountSaved>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
    }

    @Test
    fun appResumed_emailConfirmed_guestLink_showsAccountSaved() = runUnitTest {
        // The deep-link/resume auto-confirm path takes the guest-link branch too:
        // the guest tapped the email link and returned to the still-live screen.
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.EmailConfirmed)
        val vm = buildVm(identity = identity, appCache = cache, guestLink = true)
        vm.takeAction(VerifyEmailAction.AppResumed)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToAccountSaved>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
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
    fun iClickedTheLink_sessionExpired_brandNewSignup_showsStillPending_doesNotTrap() = runUnitTest {
        // AUTH-25: a brand-new signup (guestLink=false) has no session until the
        // confirmation link is tapped, so "no session" on an explicit check means
        // "not confirmed yet" — surface the StillPending nudge, never bounce the
        // user to "Welcome back" and clear their back stack.
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = false))
        val vm = buildVm(
            identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.SessionExpired),
            appCache = cache,
        )
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner != VerifyEmailState.Banner.StillPending) last = awaitItem()
            assertEquals(false, last.isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            false, cache.get().hasUserOnboarded,
            "no session must not flip onboarding done",
        )
    }

    @Test
    fun iClickedTheLink_sessionExpired_guestLink_emitsNavigateBackToSignIn() = runUnitTest {
        // A guest who linked an email did have a live session; a genuine expiry
        // there still routes back to sign in.
        val vm = buildVm(
            identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.SessionExpired),
            guestLink = true,
        )
        vm.takeAction(VerifyEmailAction.IClickedTheLink)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateBackToSignIn>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
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
    fun appResumed_emailConfirmed_returning_marksOnboarded_andNavigatesHome() = runUnitTest {
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.EmailConfirmed)
        val profile = FakeProfileRepository().apply { isNewAccount = false }
        val vm = buildVm(identity = identity, appCache = cache, profile = profile)
        vm.takeAction(VerifyEmailAction.AppResumed)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToHome>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
        assertEquals(1, identity.refreshCalls)
    }

    @Test
    fun appResumed_emailConfirmed_brandNew_reentersOnboarding() = runUnitTest {
        // The deep-link/resume auto-confirm path branches the same as the
        // manual button: a brand-new account re-enters onboarding.
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.EmailConfirmed)
        val profile = FakeProfileRepository().apply { isNewAccount = true }
        val vm = buildVm(identity = identity, appCache = cache, profile = profile)
        vm.takeAction(VerifyEmailAction.AppResumed)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateToOnboarding>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, cache.get().hasUserOnboarded)
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
    fun appResumed_sessionExpired_brandNewSignup_isSilent_doesNotTrap() = runUnitTest {
        // AUTH-25 regression: VerifyEmailScreen fires AppResumed on mount. For a
        // brand-new signup there's no session yet, so refreshSession reports
        // SessionExpired — which used to yank the user straight to "Welcome back"
        // (back stack cleared) the instant the verify screen appeared. The silent
        // resume path must stay put and wait for confirmation.
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.SessionExpired)
        val vm = buildVm(identity = identity, appCache = cache)

        // Events are buffered on an UNLIMITED channel, so taking the action first
        // then collecting still catches any navigation that fired.
        vm.takeAction(VerifyEmailAction.AppResumed)
        runCurrent()
        vm.eventFlow.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.refreshCalls)
        assertEquals(null, vm.stateFlow.value.banner)
        assertEquals(false, cache.get().hasUserOnboarded)
    }

    @Test
    fun appResumed_sessionExpired_guestLink_emitsNavigateBackToSignIn() = runUnitTest {
        // A guest whose linked-email session genuinely died still routes back.
        val identity = FakeAuthRepository(refreshOutcome = RefreshOutcome.SessionExpired)
        val vm = buildVm(identity = identity, guestLink = true)
        vm.takeAction(VerifyEmailAction.AppResumed)

        vm.eventFlow.test {
            assertIs<VerifyEmailEvent.NavigateBackToSignIn>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun init_emailNull_resolvesFromSession_andUpdatesState() = runUnitTest {
        // Cold-launch deep-link path: cards://auth/confirmed lands without
        // an email arg. VM must pick the address up from the current
        // Supabase session so the screen + Resend can use it.
        val sessionEmail = "session@example.com"
        val identity = FakeAuthRepository(
            initialAuthState = AuthState.Authenticated(
                userId = "user-1",
                isAnonymous = false,
                email = sessionEmail,
            ),
        )
        val vm = buildVm(identity = identity, email = null)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.email.isEmpty()) last = awaitItem()
            assertEquals(sessionEmail, last.email)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun init_emailNull_sessionEmailMissing_keepsEmptyEmail() = runUnitTest {
        // Authenticated session without an email (anonymous-only) leaves
        // the field empty so the screen renders the no-email body string;
        // Resend stays disabled at the UI layer.
        val identity = FakeAuthRepository(
            initialAuthState = AuthState.Authenticated(
                userId = "anon-1",
                isAnonymous = true,
                email = null,
            ),
        )
        val vm = buildVm(identity = identity, email = null)

        // Let the init-driven resolve action run through the action loop.
        runCurrent()
        assertEquals("", vm.stateFlow.value.email)
    }

    @Test
    fun init_emailProvided_doesNotConsultSession() = runUnitTest {
        // The same-device flow hands the email in via the route; the VM
        // must not stomp it with a session read.
        val identity = FakeAuthRepository(
            initialAuthState = AuthState.Authenticated(
                userId = "user-1",
                isAnonymous = false,
                email = "session@example.com",
            ),
        )
        val vm = buildVm(identity = identity, email = "route@example.com")

        // Drain the action loop so any spurious init action would land.
        runCurrent()
        assertEquals("route@example.com", vm.stateFlow.value.email)
    }

    @Test
    fun resend_emptyEmail_noOp_doesNotCallRepo() = runUnitTest {
        // Resend without a resolved email must not fire a no-target request.
        val identity = FakeAuthRepository(
            initialAuthState = AuthState.Unauthenticated(),
            resendOutcome = ResendOutcome.Sent,
        )
        val vm = buildVm(identity = identity, email = null)
        vm.takeAction(VerifyEmailAction.Resend)

        runCurrent()
        assertEquals(0, identity.resendCalls)
        assertEquals(null, vm.stateFlow.value.banner)
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeAuthRepository = FakeAuthRepository(),
        appCache: FakeAppCache = FakeAppCache(),
        profile: FakeProfileRepository = FakeProfileRepository(),
        email: String? = sampleEmail,
        guestLink: Boolean = false,
    ): VerifyEmailViewModel = VerifyEmailViewModel(
        authRepository = identity,
        appCache = appCache,
        profileRepository = profile,
        email = email,
        guestLink = guestLink,
    )
}
