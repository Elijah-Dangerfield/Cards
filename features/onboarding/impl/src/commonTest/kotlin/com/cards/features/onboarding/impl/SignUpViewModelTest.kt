package com.dangerfield.cards.features.onboarding.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [SignUpViewModel]'s outcome mapping. The big invariant: on
 * VerificationRequired the VM emits NavigateToVerifyEmail with the
 * server's normalized email (NOT whatever the user typed) — that's
 * the only handle the verify screen has to resend the email.
 *
 * What we pin:
 *  - canSubmit gates on '@' AND password ≥ 6 chars
 *  - Submit short-circuits when canSubmit is false
 *  - VerificationRequired → NavigateToVerifyEmail with the server's email
 *  - EmailAlreadyRegistered surfaces a "try signing in" hint
 *  - WeakPassword surfaces a strength hint
 *  - InvalidEmail surfaces an email-shape hint
 *  - NetworkError surfaces a network-shaped message
 *  - Submit trims the email before the network call
 *  - DismissError clears the error
 */
class SignUpViewModelTest : CoroutineTest() {

    @Test
    fun canSubmit_isFalseUntilEmailAndPasswordValid() = runUnitTest {
        val vm = buildVm()
        assertEquals(false, vm.state.canSubmit)

        vm.takeAction(SignUpAction.EmailChanged("nope"))
        vm.takeAction(SignUpAction.PasswordChanged("12345"))
        assertEquals(false, vm.state.canSubmit)

        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("123456"))
        assertEquals(true, vm.state.canSubmit)
    }

    @Test
    fun submit_whenCantSubmit_doesNotCallRepo() = runUnitTest {
        val identity = FakeIdentityRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.Submit) // blank
        assertEquals(0, identity.signUpCalls)
    }

    @Test
    fun submit_verificationRequired_emitsNavigateToVerifyWithServerEmail() = runUnitTest {
        // Important: the verify screen needs the server's normalized
        // email (lower-cased / trimmed) to call resendVerificationEmail
        // against the same record. The VM must pass through whatever
        // the server says, not whatever the user typed.
        val vm = buildVm(
            identity = FakeIdentityRepository(
                signUpOutcome = SignUpOutcome.VerificationRequired("ok@example.com"),
            ),
        )
        vm.takeAction(SignUpAction.EmailChanged("OK@Example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.eventFlow.test {
            val event = assertIs<SignUpEvent.NavigateToVerifyEmail>(awaitItem())
            assertEquals("ok@example.com", event.email)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_emailAlreadyRegistered_surfacesSignInHint() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(signUpOutcome = SignUpOutcome.EmailAlreadyRegistered),
        )
        vm.takeAction(SignUpAction.EmailChanged("dup@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("signing in", ignoreCase = true),
                "expected a 'try signing in' hint, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_weakPassword_surfacesStrengthHint() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(signUpOutcome = SignUpOutcome.WeakPassword),
        )
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("stronger", ignoreCase = true),
                "expected a strength hint, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_invalidEmail_surfacesShapeHint() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(signUpOutcome = SignUpOutcome.InvalidEmail),
        )
        // canSubmit only checks for '@' so a server-side reject is the
        // only way to surface "that email doesn't look right" here.
        vm.takeAction(SignUpAction.EmailChanged("weird@x"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("doesn't look right", ignoreCase = true),
                "expected email-shape hint, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_networkError_surfacesNetworkMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                signUpOutcome = SignUpOutcome.NetworkError(RuntimeException("nope")),
            ),
        )
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("reach", ignoreCase = true) ||
                    last.error!!.contains("connection", ignoreCase = true),
                "expected a network-shaped error, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_trimsEmail_beforeNetworkCall() = runUnitTest {
        val identity = FakeIdentityRepository(
            signUpOutcome = SignUpOutcome.VerificationRequired("ok@example.com"),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.EmailChanged("  ok@example.com  "))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.eventFlow.test {
            awaitItem() // NavigateToVerifyEmail
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("ok@example.com", identity.lastSignUpArgs?.first)
    }

    @Test
    fun dismissError_clearsError() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(signUpOutcome = SignUpOutcome.EmailAlreadyRegistered),
        )
        vm.takeAction(SignUpAction.EmailChanged("dup@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(SignUpAction.DismissError)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error != null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeIdentityRepository = FakeIdentityRepository(),
    ): SignUpViewModel = SignUpViewModel(identityRepository = identity)
}
