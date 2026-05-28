package com.dangerfield.cards.features.onboarding.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [ForgotPasswordViewModel]'s outcome → state mapping. Same shape as
 * [SignInViewModelTest] — the VM is a thin orchestrator over
 * [com.dangerfield.cards.libraries.identity.auth.AuthRepository], so the
 * assertions stay on the branching: which banner renders, when
 * `isSubmitting` clears, when `sent` flips.
 *
 * What we pin:
 *  - canSubmit gates on email containing '@' and ignores blank input
 *  - Submit with !canSubmit short-circuits before any network call
 *  - Submit while already-sent short-circuits — the screen has switched to
 *    the success state and the button has been replaced
 *  - Sent flips `sent = true` and clears `isSubmitting`
 *  - RateLimited surfaces the rate-limit banner + clears submitting
 *  - NetworkError surfaces the network banner + clears submitting
 *  - Unknown surfaces the generic banner + clears submitting
 *  - Email is trimmed before the network call
 *  - typing into email after a banner clears the banner
 */
class ForgotPasswordViewModelTest : CoroutineTest() {

    @Test
    fun canSubmit_isFalseUntilEmailContainsAt() = runUnitTest {
        val vm = buildVm()
        assertEquals(false, vm.state.canSubmit)

        vm.takeAction(ForgotPasswordAction.EmailChanged("notanemail"))
        assertEquals(false, vm.state.canSubmit)

        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        assertEquals(true, vm.state.canSubmit)
    }

    @Test
    fun submit_whenCantSubmit_doesNotCallRepo() = runUnitTest {
        val identity = FakeAuthRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(ForgotPasswordAction.Submit)
        assertEquals(0, identity.sendResetCalls)
    }

    @Test
    fun submit_afterSent_doesNotCallRepoAgain() = runUnitTest {
        val identity = FakeAuthRepository(sendResetOutcome = SendResetOutcome.Sent)
        val vm = buildVm(identity = identity)
        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        vm.takeAction(ForgotPasswordAction.Submit)
        vm.stateFlow.test {
            var last = awaitItem()
            while (!last.sent) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.sendResetCalls)
        vm.takeAction(ForgotPasswordAction.Submit)
        assertEquals(1, identity.sendResetCalls, "second submit short-circuits after sent")
    }

    @Test
    fun submit_sent_flipsSent_andClearsSubmitting() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(sendResetOutcome = SendResetOutcome.Sent),
        )
        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        vm.takeAction(ForgotPasswordAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (!last.sent) last = awaitItem()
            assertEquals(false, last.isSubmitting)
            assertNull(last.banner)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_rateLimited_surfacesBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(sendResetOutcome = SendResetOutcome.RateLimited),
        )
        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        vm.takeAction(ForgotPasswordAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner == null) last = awaitItem()
            assertEquals(ForgotPasswordState.Banner.RateLimited, last.banner)
            assertEquals(false, last.isSubmitting)
            assertEquals(false, last.sent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_networkError_surfacesBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                sendResetOutcome = SendResetOutcome.NetworkError(RuntimeException("offline")),
            ),
        )
        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        vm.takeAction(ForgotPasswordAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner == null) last = awaitItem()
            assertEquals(ForgotPasswordState.Banner.NetworkError, last.banner)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_unknownError_surfacesGenericBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                sendResetOutcome = SendResetOutcome.Unknown(RuntimeException("boom")),
            ),
        )
        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        vm.takeAction(ForgotPasswordAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner == null) last = awaitItem()
            assertEquals(ForgotPasswordState.Banner.GenericError, last.banner)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_trimsEmail_beforeNetworkCall() = runUnitTest {
        val identity = FakeAuthRepository(sendResetOutcome = SendResetOutcome.Sent)
        val vm = buildVm(identity = identity)
        vm.takeAction(ForgotPasswordAction.EmailChanged("  ok@example.com  "))
        vm.takeAction(ForgotPasswordAction.Submit)
        vm.stateFlow.test {
            var last = awaitItem()
            while (!last.sent) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("ok@example.com", identity.lastSendResetEmail)
    }

    @Test
    fun emailChanged_clearsExistingBanner() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(sendResetOutcome = SendResetOutcome.RateLimited),
        )
        vm.takeAction(ForgotPasswordAction.EmailChanged("ok@example.com"))
        vm.takeAction(ForgotPasswordAction.Submit)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.banner == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.takeAction(ForgotPasswordAction.EmailChanged("o@example.com"))
        assertNull(vm.state.banner)
        assertTrue(vm.state.canSubmit)
    }

    private fun buildVm(
        identity: FakeAuthRepository = FakeAuthRepository(),
    ): ForgotPasswordViewModel = ForgotPasswordViewModel(authRepository = identity)
}
