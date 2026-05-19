package com.dangerfield.cards.features.profile.impl.account

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.OAuthProvider
import com.dangerfield.cards.libraries.identity.SignInOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 *  - AlreadyOnAnotherAccount stashes conflictingProvider + surfaces a warning,
 *    does NOT invoke signInWithOAuth
 *  - ConfirmSwitchToExisting without a conflict short-circuits (no repo call)
 *  - ConfirmSwitchToExisting after a conflict invokes signInWithOAuth and
 *    emits SwitchedAccounts on success
 *  - NotSignedIn surfaces a "sign in first" message
 *  - Cancelled clears submitting without showing an error
 *  - ProviderNotEnabled surfaces a provider-specific message
 *  - NetworkError / Unknown surface their respective shapes
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
        assertEquals(true, vmEnabled.state.appleEnabled)
        assertEquals(true, vmEnabled.state.anyProviderEnabled)

        val vmGoogleOnly = buildVm(
            config = TestAppConfigMap.withOAuthEnabled(google = true, apple = false),
        )
        assertEquals(true, vmGoogleOnly.state.googleEnabled)
        assertEquals(false, vmGoogleOnly.state.appleEnabled)
    }

    @Test
    fun claim_success_emitsClaimedEvent_andClearsSubmitting() = runUnitTest {
        val identity = FakeIdentityRepository(
            linkOutcome = LinkIdentityOutcome.Success(sampleIdentity),
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
        val identity = FakeIdentityRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
        )
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(OAuthProvider.Apple, last.conflictingProvider)
            assertTrue(
                last.error!!.contains("already in use", ignoreCase = true) ||
                    last.error!!.contains("won't carry over", ignoreCase = true),
                "expected the guest-progress-loss warning, got: ${last.error}",
            )
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
        val identity = FakeIdentityRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(ClaimAccountAction.ConfirmSwitchToExisting)
        assertEquals(
            0, identity.oauthSignInCalls,
            "ConfirmSwitchToExisting only fires after AlreadyOnAnotherAccount",
        )
    }

    @Test
    fun confirmSwitch_afterConflict_callsSignInWithOAuth_andEmitsSwitched() = runUnitTest {
        val identity = FakeIdentityRepository(
            linkOutcome = LinkIdentityOutcome.AlreadyOnAnotherAccount,
            oauthSignInOutcome = SignInOutcome.Success(sampleIdentity),
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
    fun claim_notSignedIn_surfacesSignInFirstMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(linkOutcome = LinkIdentityOutcome.NotSignedIn),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("sign in", ignoreCase = true),
                "expected a sign-in-first hint, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_cancelled_clearsSubmitting_andDoesNotShowError() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(linkOutcome = LinkIdentityOutcome.Cancelled),
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
    fun claim_providerNotEnabled_surfacesProviderMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(linkOutcome = LinkIdentityOutcome.ProviderNotEnabled),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("Google", ignoreCase = true),
                "ProviderNotEnabled error must name the provider, got: ${last.error}",
            )
            assertTrue(
                last.error!!.contains("isn't available", ignoreCase = true) ||
                    last.error!!.contains("not available", ignoreCase = true),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_networkError_surfacesNetworkMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                linkOutcome = LinkIdentityOutcome.NetworkError(RuntimeException("offline")),
            ),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("reach", ignoreCase = true) ||
                    last.error!!.contains("connection", ignoreCase = true),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_unknown_surfacesGenericMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                linkOutcome = LinkIdentityOutcome.Unknown(RuntimeException("boom")),
            ),
        )
        vm.takeAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertNotNull(last.error)
            assertEquals(false, last.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun confirmSwitch_signInNetworkError_surfacesNetworkMessage() = runUnitTest {
        val identity = FakeIdentityRepository(
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
            assertTrue(
                last.error!!.contains("reach", ignoreCase = true) ||
                    last.error!!.contains("connection", ignoreCase = true),
            )
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
            identity = FakeIdentityRepository(
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

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeIdentityRepository = FakeIdentityRepository(),
        config: TestAppConfigMap = TestAppConfigMap.withOAuthEnabled(),
    ): ClaimAccountViewModel = ClaimAccountViewModel(
        identityRepository = identity,
        appConfigMap = config,
    )
}
