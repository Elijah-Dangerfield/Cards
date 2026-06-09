package com.dangerfield.cards.features.onboarding.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins [SignInViewModel]'s outcome → state mapping. The VM is a thin
 * orchestrator over [com.dangerfield.cards.libraries.identity.auth.AuthRepository],
 * so the assertions stay on the branching: which error message renders,
 * when `isSubmitting` clears, when `hasUserOnboarded` flips, when an
 * event fires.
 *
 * What we pin:
 *  - canSubmit gates on email containing '@' AND password ≥ 6 chars
 *  - Submit with !canSubmit short-circuits before any network call
 *  - InvalidCredentials surfaces a specific error + clears isSubmitting
 *  - NetworkError surfaces a network-specific message
 *  - EmailNotConfirmed emits NavigateToVerifyEmail and does NOT mark onboarded
 *  - Success marks onboarded and emits NavigateToHome
 *  - SignInWithOAuth threads through; ProviderNotEnabled surfaces an error
 *  - Email is trimmed before the network call
 *  - typing on email after an error clears the error
 *  - OAuth Success marks onboarded and emits NavigateToHome
 */
class SignInViewModelTest : CoroutineTest() {

    @Test
    fun canSubmit_isFalseUntilEmailAndPasswordValid() = runUnitTest {
        val vm = buildVm()
        // Initial state — both empty, can't submit.
        assertEquals(false, vm.state.canSubmit)

        vm.takeAction(SignInAction.EmailChanged("nope"))
        vm.takeAction(SignInAction.PasswordChanged("short"))
        assertEquals(false, vm.state.canSubmit, "no '@' and password < 6")

        vm.takeAction(SignInAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignInAction.PasswordChanged("123456"))
        assertEquals(true, vm.state.canSubmit)
    }

    @Test
    fun submit_whenCantSubmit_doesNotCallRepo() = runUnitTest {
        val identity = FakeAuthRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(SignInAction.Submit) // both fields blank
        assertEquals(0, identity.signInCalls, "submit short-circuits without valid inputs")
    }

    @Test
    fun submit_invalidCredentials_surfacesError_andClearsSubmitting() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(signInOutcome = SignInOutcome.InvalidCredentials),
        )
        vm.takeAction(SignInAction.EmailChanged("a@b.com"))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(false, last.isSubmitting, "isSubmitting clears on failure")
            assertEquals(SignInError.InvalidCredentials, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_networkError_surfacesNetworkMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                signInOutcome = SignInOutcome.NetworkError(RuntimeException("nope")),
            ),
        )
        vm.takeAction(SignInAction.EmailChanged("a@b.com"))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(SignInError.NetworkError, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_unknown_surfacesUnknownError() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(
                signInOutcome = SignInOutcome.Unknown(RuntimeException("boom")),
            ),
        )
        vm.takeAction(SignInAction.EmailChanged("a@b.com"))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(SignInError.Unknown, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_emailNotConfirmed_emitsNavigateToVerify_andDoesNotOnboard() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(
            identity = FakeAuthRepository(
                signInOutcome = SignInOutcome.EmailNotConfirmed("user@example.com"),
            ),
            appCache = cache,
        )
        vm.takeAction(SignInAction.EmailChanged("user@example.com"))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)

        vm.eventFlow.test {
            val event = awaitItem()
            val verify = assertIs<SignInEvent.NavigateToVerifyEmail>(event)
            assertEquals("user@example.com", verify.email)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            false, cache.get().hasUserOnboarded,
            "unverified email must not flip onboarding done",
        )
    }

    @Test
    fun submit_success_marksOnboarded_andEmitsNavigateToHome() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = false))
        val identity = FakeAuthRepository(
            signInOutcome = SignInOutcome.Success,
        )
        val vm = buildVm(identity = identity, appCache = cache)
        vm.takeAction(SignInAction.EmailChanged("ok@example.com"))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)

        vm.eventFlow.test {
            assertIs<SignInEvent.NavigateToHome>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
    }

    @Test
    fun submit_trimsEmail_beforeNetworkCall() = runUnitTest {
        val identity = FakeAuthRepository(
            signInOutcome = SignInOutcome.Success,
        )
        val vm = buildVm(identity = identity)
        // Note: leading and trailing whitespace; canSubmit still passes
        // because the raw string contains '@'.
        vm.takeAction(SignInAction.EmailChanged("  ok@example.com  "))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)

        vm.eventFlow.test {
            awaitItem() // NavigateToHome
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "ok@example.com",
            identity.lastSignInArgs?.first,
            "email passed to the repo must be trimmed",
        )
    }

    @Test
    fun emailChanged_clearsExistingError() = runUnitTest {
        val vm = buildVm(
            identity = FakeAuthRepository(signInOutcome = SignInOutcome.InvalidCredentials),
        )
        vm.takeAction(SignInAction.EmailChanged("a@b.com"))
        vm.takeAction(SignInAction.PasswordChanged("password"))
        vm.takeAction(SignInAction.Submit)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // Typing into the email field should clear the error so the
        // form looks "fresh" before the next submit.
        vm.takeAction(SignInAction.EmailChanged("a@b.co"))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error != null) last = awaitItem()
            assertNull(last.error, "typing into email clears the inline error")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun signInWithOAuth_providerNotEnabled_surfacesError() = runUnitTest {
        val identity = FakeAuthRepository(
            oauthSignInOutcome = SignInOutcome.ProviderNotEnabled,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(SignInAction.SignInWithOAuth(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(SignInError.ProviderNotEnabled, last.error)
            assertEquals(false, last.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(OAuthProvider.Google, identity.lastOAuthProvider)
    }

    @Test
    fun signInWithOAuth_success_marksOnboarded_andNavigates() = runUnitTest {
        val cache = FakeAppCache()
        val identity = FakeAuthRepository(
            oauthSignInOutcome = SignInOutcome.Success,
        )
        val vm = buildVm(identity = identity, appCache = cache)
        vm.takeAction(SignInAction.SignInWithOAuth(OAuthProvider.Apple))

        vm.eventFlow.test {
            assertIs<SignInEvent.NavigateToHome>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, cache.get().hasUserOnboarded)
    }

    @Test
    fun signInWithOAuth_cancelled_clearsSubmitting_andNoError() = runUnitTest {
        // The user dismissing the OAuth tab is not a "failure" from
        // their perspective — UI should not show a red error banner.
        val vm = buildVm(
            identity = FakeAuthRepository(
                oauthSignInOutcome = SignInOutcome.Cancelled,
            ),
        )
        vm.takeAction(SignInAction.SignInWithOAuth(OAuthProvider.Google))

        // Pump state until isSubmitting clears.
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isSubmitting) last = awaitItem()
            assertNull(last.error, "cancelled OAuth must not surface an error")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeAuthRepository = FakeAuthRepository(),
        appCache: FakeAppCache = FakeAppCache(),
    ): SignInViewModel {
        val config = EmptyAppConfigMap()
        return SignInViewModel(
            authRepository = identity,
            appCache = appCache,
            appleSignInCoordinator = NoopAppleSignInCoordinator,
            googleSignInEnabled = GoogleSignInEnabled(config),
            appleSignInEnabled = AppleSignInEnabled(config),
        )
    }

    /** No test here exercises the iOS-only native Apple sign-in; the coordinator is a no-op. */
    private object NoopAppleSignInCoordinator :
        com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator {
        override fun requestCredential(
            onComplete: (com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential?, String?) -> Unit,
        ) = onComplete(null, null)
    }
}
