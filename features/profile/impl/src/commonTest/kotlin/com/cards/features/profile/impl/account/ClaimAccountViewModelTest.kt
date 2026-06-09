package com.dangerfield.cards.features.profile.impl.account

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins [ClaimAccountViewModel]'s outcome → state mapping. The interesting
 * branching is `AlreadyOnAnotherAccount` — the VM must NOT auto-switch;
 * it stashes the provider and surfaces the "your guest progress won't
 * carry over" warning. Only an explicit `ConfirmSwitchToExisting` flips
 * to `signInWithOAuth`, and only then does an event fire.
 *
 * What we pin:
 *  - Initial provider gates honor the AppConfigMap flags
 *  - linkOAuthIdentity Success emits Claimed event
 *  - AlreadyOnAnotherAccount stashes conflictingProvider + surfaces the
 *    typed sealed variant, does NOT invoke signInWithOAuth
 *  - ConfirmSwitchToExisting without a conflict short-circuits (no repo call)
 *  - ConfirmSwitchToExisting after a conflict invokes signInWithOAuth and
 *    emits SwitchedAccounts on success
 *  - NotSignedIn surfaces the typed NotSignedIn variant
 *  - Cancelled clears submitting without showing an error
 *  - ProviderNotEnabled surfaces the typed variant carrying the provider
 *  - NetworkError / Unknown surface their respective typed variants
 *  - DismissError clears both error AND conflictingProvider
 */
class ClaimAccountViewModelTest : CoroutineTest() {

    @Test
    fun initialState_reflectsOAuthFeatureFlags_fromConfig() = runUnitTest {
        val vmDisabled = buildVm(config = TestAppConfigMap())
        assertEquals(false, vmDisabled.state.googleEnabled)
        assertEquals(false, vmDisabled.state.appleEnabled)
        assertEquals(false, vmDisabled.state.anyProviderEnabled)

        val vmEnabled = buildVm(
            config = TestAppConfigMap.withOAuthEnabled(google = true, apple = true),
        )
        assertEquals(true, vmEnabled.state.googleEnabled)
        // Apple is additionally gated on iOS (native flow only); this unit test
        // runs on the JVM, so it's off even with the flag enabled.
        assertEquals(false, vmEnabled.state.appleEnabled)
        assertEquals(true, vmEnabled.state.anyProviderEnabled)

        val vmGoogleOnly = buildVm(
            config = TestAppConfigMap.withOAuthEnabled(google = true, apple = false),
        )
        assertEquals(true, vmGoogleOnly.state.googleEnabled)
        assertEquals(false, vmGoogleOnly.state.appleEnabled)
    }

    @Test
    fun claim_success_emitsClaimedEvent_andClearsSubmitting() = runUnitTest {
        val identity = FakeAuthRepository(
            linkOutcome = LinkIdentityOutcome.Success,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.eventFlow.test {
            assertIs<ClaimAccountEvent.Claimed>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(OAuthProvider.Google, identity.lastLinkProvider)
        assertEquals(false, vm.state.isSubmitting)
    }

    @Test
    fun claim_alreadyOnAnotherAccount_stashesConflict_andSurfacesWarning() = runUnitTest {
        // The key invariant: this path must NOT auto-call signInWithOAuth.
        // The user has to explicitly confirm losing guest progress first.
        val identity = FakeAuthRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(OAuthProvider.Apple, last.conflictingProvider)
            assertEquals(ClaimAccountError.AlreadyOnAnotherAccount, last.error)
            assertEquals(false, last.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            0, identity.oauthSignInCalls,
            "AlreadyOnAnotherAccount must NOT auto-trigger signInWithOAuth — user must confirm",
        )
    }

    @Test
    fun confirmSwitch_withoutConflict_doesNothing() = runUnitTest {
        val identity = FakeAuthRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.ConfirmSwitchToExisting)
        assertEquals(
            0, identity.oauthSignInCalls,
            "ConfirmSwitchToExisting only fires after AlreadyOnAnotherAccount",
        )
    }

    @Test
    fun confirmSwitch_afterConflict_callsSignInWithOAuth_andEmitsSwitched() = runUnitTest {
        val identity = FakeAuthRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            oauthSignInOutcome = SignInOutcome.Success,
        )
        val vm = buildVm(identity = identity)

        // First produce the conflict.
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.conflictingProvider == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // User accepts the trade-off → switch to the existing account.
        vm.takeAction(ClaimAccountAction.ConfirmSwitchToExisting)

        vm.eventFlow.test {
            assertIs<ClaimAccountEvent.SwitchedAccounts>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.oauthSignInCalls)
        assertEquals(OAuthProvider.Apple, identity.lastOAuthProvider)
        // The conflict must clear once the user has acted on it.
        assertNull(vm.state.conflictingProvider)
        assertNull(vm.state.error)
    }

    @Test
    fun claim_notSignedIn_surfacesNotSignedIn() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(linkOutcome = LinkIdentityOutcome.NotSignedIn),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(ClaimAccountError.NotSignedIn, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_cancelled_clearsSubmitting_andDoesNotShowError() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(linkOutcome = LinkIdentityOutcome.Cancelled),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isSubmitting) last = awaitItem()
            assertNull(last.error, "cancelled OAuth must not surface an error")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_providerNotEnabled_surfacesProviderNotEnabledWithProvider() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(linkOutcome = LinkIdentityOutcome.ProviderNotEnabled),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(
                ClaimAccountError.ProviderNotEnabled(OAuthProvider.Google),
                last.error,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_networkError_surfacesNetworkError() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                linkOutcome = LinkIdentityOutcome.NetworkError(RuntimeException("offline")),
            ),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(ClaimAccountError.NetworkError, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_unknown_surfacesUnknown() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                linkOutcome = LinkIdentityOutcome.Unknown(RuntimeException("boom")),
            ),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(ClaimAccountError.Unknown, last.error)
            assertEquals(false, last.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun confirmSwitch_signInNetworkError_surfacesNetworkError() = runUnitTest {
        val identity = FakeAuthRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            oauthSignInOutcome = SignInOutcome.NetworkError(RuntimeException("offline")),
        )
        val vm = buildVm(identity = identity)

        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.conflictingProvider == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(ClaimAccountAction.ConfirmSwitchToExisting)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null || last.isSubmitting) last = awaitItem()
            assertEquals(ClaimAccountError.NetworkError, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun confirmSwitch_signInProviderNotEnabled_surfacesProviderNotEnabledWithProvider() = runUnitTest {
        val identity = FakeAuthRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            oauthSignInOutcome = SignInOutcome.ProviderNotEnabled,
        )
        val vm = buildVm(identity = identity)

        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.conflictingProvider == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(ClaimAccountAction.ConfirmSwitchToExisting)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null || last.isSubmitting) last = awaitItem()
            assertEquals(
                ClaimAccountError.ProviderNotEnabled(OAuthProvider.Apple),
                last.error,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun confirmSwitch_signInUnknown_surfacesSwitchFailed() = runUnitTest {
        val identity = FakeAuthRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            oauthSignInOutcome = SignInOutcome.Unknown(RuntimeException("boom")),
        )
        val vm = buildVm(identity = identity)

        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.conflictingProvider == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(ClaimAccountAction.ConfirmSwitchToExisting)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null || last.isSubmitting) last = awaitItem()
            assertEquals(ClaimAccountError.SwitchFailed, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun dismissError_clearsErrorAndConflict() = runUnitTest {
        // After AlreadyOnAnotherAccount the user might cancel out of the
        // dialog entirely. DismissError must clear both the warning AND
        // the stashed conflictingProvider so a fresh claim attempt starts
        // from a clean slate.
        val vm = buildVm(
            identity = FakeAuthRepository(
                linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            ),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.conflictingProvider == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(ClaimAccountAction.DismissError)
        val s = vm.state
        assertNull(s.error)
        assertNull(s.conflictingProvider, "DismissError must also drop the stashed conflict")
    }

    // ---------- email / password path ----------

    @Test
    fun emailSubmit_belowMinimum_doesNotFireLink() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.VerificationRequired("you@example.com"),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("short"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("short"))

        vm.takeAction(ClaimAccountAction.Submit)
        assertEquals(0, identity.linkEmailCalls, "Below MIN_PASSWORD_LENGTH must short-circuit before any repo call")
    }

    @Test
    fun emailSubmit_passwordMismatch_isDetectedAndBlocksSubmit() = runUnitTest {
        val identity = FakeAuthRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22is"))

        assertEquals(true, vm.state.passwordMismatch)
        assertEquals(false, vm.state.canSubmit, "canSubmit must be false when passwords don't match")
        vm.takeAction(ClaimAccountAction.Submit)
        assertEquals(0, identity.linkEmailCalls)
    }

    @Test
    fun emailClaim_verificationRequired_emitsNavigateToVerifyEmail() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.VerificationRequired("you@example.com"),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.Submit)

        vm.eventFlow.test {
            val event = awaitItem()
            assertIs<ClaimAccountEvent.NavigateToVerifyEmail>(event)
            assertEquals("you@example.com", event.email)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.linkEmailCalls)
        assertEquals("you@example.com" to "hunter22ish", identity.lastLinkEmail)
        assertEquals(false, vm.state.isSubmitting)
    }

    @Test
    fun emailClaim_emailAlreadyRegistered_surfacesEmailAlreadyRegistered() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.EmailAlreadyRegistered,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("taken@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(ClaimAccountError.EmailAlreadyRegistered, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emailClaim_weakPassword_surfacesWeakPasswordWithMinLength() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.WeakPassword,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(
                ClaimAccountError.WeakPassword(ClaimAccountState.MIN_PASSWORD_LENGTH),
                last.error,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emailClaim_invalidEmail_surfacesInvalidEmail() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.InvalidEmail,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(ClaimAccountError.InvalidEmail, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emailClaim_notAnonymous_fallsBackToSignUp() = runUnitTest {
        // If the session is no longer anonymous, linkEmailIdentity refuses.
        // The VM falls back to signUpWithEmail rather than dead-ending the user.
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.NotAnonymous,
            signUpOutcome = SignUpOutcome.VerificationRequired("you@example.com"),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.Submit)

        vm.eventFlow.test {
            assertIs<ClaimAccountEvent.NavigateToVerifyEmail>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, identity.linkEmailCalls)
        assertEquals(1, identity.signUpCalls)
    }

    @Test
    fun emailClaim_networkError_surfacesNetworkError() = runUnitTest {
        val identity = FakeAuthRepository(
            linkEmailOutcome = LinkEmailIdentityOutcome.NetworkError(RuntimeException("offline")),
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.EmailChanged("you@example.com"))
        vm.takeAction(ClaimAccountAction.PasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.ConfirmPasswordChanged("hunter22ish"))
        vm.takeAction(ClaimAccountAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(ClaimAccountError.NetworkError, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeAuthRepository = FakeAuthRepository(),
        config: TestAppConfigMap = TestAppConfigMap.withOAuthEnabled(),
    ): ClaimAccountViewModel = ClaimAccountViewModel(
        authRepository = identity,
        appleSignInCoordinator = NoopAppleSignInCoordinator,
        googleSignInEnabled = GoogleSignInEnabled(config),
        appleSignInEnabled = AppleSignInEnabled(config),
    )

    /** No test here exercises the iOS-only native Apple claim; the coordinator is a no-op. */
    private object NoopAppleSignInCoordinator :
        com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator {
        override fun requestCredential(
            onComplete: (com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential?, String?) -> Unit,
        ) = onComplete(null, null)
    }
}
