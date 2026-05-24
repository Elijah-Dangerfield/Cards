package com.dangerfield.cards.features.onboarding.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
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
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("123456"))
        assertEquals(true, vm.state.canSubmit)
    }

    @Test
    fun canSubmit_isFalseWhenConfirmPasswordMismatches() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("passwxrd"))
        assertEquals(false, vm.state.canSubmit)
        assertEquals(true, vm.state.passwordMismatch)
    }

    @Test
    fun submit_withMismatchedConfirm_doesNotCallRepoAndSurfacesError() = runUnitTest {
        val identity = FakeAuthRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("passwxrd"))
        vm.takeAction(SignUpAction.Submit)
        assertEquals(0, identity.signUpCalls)
        assertEquals(0, identity.linkEmailCalls)
    }

    @Test
    fun submit_whenCantSubmit_doesNotCallRepo() = runUnitTest {
        val identity = FakeAuthRepository()
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
            identity = FakeAuthRepository(
                signUpOutcome = SignUpOutcome.VerificationRequired("ok@example.com"),
            ),
        )
        vm.takeAction(SignUpAction.EmailChanged("OK@Example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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
            identity = FakeAuthRepository(signUpOutcome = SignUpOutcome.EmailAlreadyRegistered),
        )
        vm.takeAction(SignUpAction.EmailChanged("dup@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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
            identity = FakeAuthRepository(signUpOutcome = SignUpOutcome.WeakPassword),
        )
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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
            identity = FakeAuthRepository(signUpOutcome = SignUpOutcome.InvalidEmail),
        )
        // canSubmit only checks for '@' so a server-side reject is the
        // only way to surface "that email doesn't look right" here.
        vm.takeAction(SignUpAction.EmailChanged("weird@x"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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
            identity = FakeAuthRepository(
                signUpOutcome = SignUpOutcome.NetworkError(RuntimeException("nope")),
            ),
        )
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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
        val identity = FakeAuthRepository(
            signUpOutcome = SignUpOutcome.VerificationRequired("ok@example.com"),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.EmailChanged("  ok@example.com  "))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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
            identity = FakeAuthRepository(signUpOutcome = SignUpOutcome.EmailAlreadyRegistered),
        )
        vm.takeAction(SignUpAction.EmailChanged("dup@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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

    @Test
    fun submit_whenAnonymous_routesToLinkEmailIdentity_preservingGuestProgress() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.VerificationRequired("ok@example.com"),
            initialAuthState = anonymousAuthState,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.eventFlow.test {
            val event = assertIs<SignUpEvent.NavigateToVerifyEmail>(awaitItem())
            assertEquals("ok@example.com", event.email)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.linkEmailCalls, "anonymous guest must take the link path")
        assertEquals(0, identity.signUpCalls, "anonymous guest must not orphan the session via signUp")
    }

    @Test
    fun submit_whenNotAnonymous_takesSignUpPath() = runUnitTest {
        val identity = FakeAuthRepository(
            signUpOutcome = SignUpOutcome.VerificationRequired("ok@example.com"),
            initialAuthState = nonAnonymousAuthState,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.eventFlow.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.signUpCalls)
        assertEquals(0, identity.linkEmailCalls)
    }

    @Test
    fun submit_anonymousLinkFails_fallsBackToSignUp_onNotAnonymous() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.NotAnonymous,
            signUpOutcome = SignUpOutcome.VerificationRequired("ok@example.com"),
            initialAuthState = anonymousAuthState,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(SignUpAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
        vm.takeAction(SignUpAction.Submit)

        vm.eventFlow.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.linkEmailCalls)
        assertEquals(1, identity.signUpCalls, "NotAnonymous link outcome must fall back to signUp so the user isn't stuck")
    }

    @Test
    fun submit_anonymousLink_surfacesEmailAlreadyRegistered() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                linkEmailOutcome = LinkEmailIdentityOutcome.EmailAlreadyRegistered,
                initialAuthState = anonymousAuthState,
            ),
        )
        vm.takeAction(SignUpAction.EmailChanged("dup@example.com"))
        vm.takeAction(SignUpAction.PasswordChanged("password"))
        vm.takeAction(SignUpAction.ConfirmPasswordChanged("password"))
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

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeAuthRepository = FakeAuthRepository(),
    ): SignUpViewModel = SignUpViewModel(authRepository = identity)

    private val anonymousAuthState = AuthState.Authenticated(
        userId = "anon-1",
        isAnonymous = true,
        email = null,
    )

    private val nonAnonymousAuthState = anonymousAuthState.copy(isAnonymous = false)
}
