package com.dangerfield.cards.features.profile.impl.account

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.DeleteAccountOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [DeleteAccountViewModel]'s outcome → state mapping. The VM is a
 * thin orchestrator over [com.dangerfield.cards.libraries.identity.IdentityRepository]
 * + [com.dangerfield.cards.libraries.cards.AppCache]; the assertions stay
 * on the branching: which message renders, when `isSubmitting` clears,
 * when `hasUserOnboarded` flips, when the event fires.
 *
 * What we pin:
 *  - canSubmit gates on the literal "delete" phrase (case-insensitive, trimmed)
 *  - Submit while !canSubmit short-circuits before any repo call
 *  - Success flips hasUserOnboarded → false and emits Deleted
 *  - NotSignedIn is treated as success (local data is already gone)
 *  - NotConfigured surfaces a "not available" message, leaves onboarding flag alone
 *  - NetworkError surfaces a network-shaped message
 *  - Unknown surfaces a generic message
 *  - Errors clear `isSubmitting` so the button isn't stuck
 *  - ConfirmationChanged clears an existing error (input value preserved)
 *  - DismissError clears the error without touching input
 */
class DeleteAccountViewModelTest : CoroutineTest() {

    @Test
    fun canSubmit_isFalseUntilConfirmationMatches() = runUnitTest {
        val vm = buildVm()
        assertEquals(false, vm.state.canSubmit, "empty input cannot submit")

        vm.takeAction(DeleteAccountAction.ConfirmationChanged("del"))
        assertEquals(false, vm.state.canSubmit, "partial match cannot submit")

        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        assertEquals(true, vm.state.canSubmit, "exact match enables submit")
    }

    @Test
    fun canSubmit_acceptsTrimmedAndCaseInsensitiveDelete() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("  DELETE  "))
        assertEquals(
            true, vm.state.canSubmit,
            "whitespace + case differences must not block destructive intent",
        )
    }

    @Test
    fun submit_whenCantSubmit_doesNotCallRepo() = runUnitTest {
        val identity = FakeIdentityRepository()
        val vm = buildVm(identity = identity)
        vm.takeAction(DeleteAccountAction.ConfirmDelete) // empty input
        assertEquals(0, identity.deleteCalls, "short-circuit before any network call")
    }

    @Test
    fun submit_success_flipsOnboardedFalse_andEmitsDeleted() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = buildVm(
            identity = FakeIdentityRepository(deleteOutcome = DeleteAccountOutcome.Success),
            appCache = cache,
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)

        vm.eventFlow.test {
            assertIs<DeleteAccountEvent.Deleted>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            false, cache.get().hasUserOnboarded,
            "successful delete must reset onboarding so the user lands at intro on next launch",
        )
    }

    @Test
    fun submit_notSignedIn_isTreatedAsSuccess() = runUnitTest {
        // No live session means there's nothing on the server tied to this
        // user anyway — from the user's POV the data they wanted gone is
        // gone. Bounce to onboarding.
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = buildVm(
            identity = FakeIdentityRepository(deleteOutcome = DeleteAccountOutcome.NotSignedIn),
            appCache = cache,
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)

        vm.eventFlow.test {
            assertIs<DeleteAccountEvent.Deleted>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, cache.get().hasUserOnboarded)
    }

    @Test
    fun submit_notConfigured_surfacesNotAvailable_andKeepsOnboardingFlag() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = buildVm(
            identity = FakeIdentityRepository(deleteOutcome = DeleteAccountOutcome.NotConfigured),
            appCache = cache,
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(false, last.isSubmitting, "isSubmitting clears on failure")
            assertTrue(
                last.error!!.contains("isn't available", ignoreCase = true) ||
                    last.error!!.contains("not available", ignoreCase = true),
                "expected a configuration-shaped error, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            true, cache.get().hasUserOnboarded,
            "failure path must not touch onboarding state",
        )
    }

    @Test
    fun submit_networkError_surfacesNetworkMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                deleteOutcome = DeleteAccountOutcome.NetworkError(RuntimeException("offline")),
            ),
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(
                last.error!!.contains("reach", ignoreCase = true) ||
                    last.error!!.contains("connection", ignoreCase = true),
                "expected a network-shaped error, got: ${last.error}",
            )
            assertEquals(false, last.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submit_unknown_surfacesGenericMessage() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                deleteOutcome = DeleteAccountOutcome.Unknown(RuntimeException("boom")),
            ),
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(false, last.isSubmitting)
            assertNotNull(last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun confirmationChanged_clearsExistingError_andPreservesInput() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                deleteOutcome = DeleteAccountOutcome.NetworkError(RuntimeException("offline")),
            ),
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // The user edits the field after a failure — the error should clear
        // so the form looks fresh, but the new input is what they typed.
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete!"))
        val s = vm.state
        assertNull(s.error, "typing into the field clears the inline error")
        assertEquals("delete!", s.confirmationInput, "input value is preserved verbatim")
    }

    @Test
    fun dismissError_clearsError_withoutMutatingInput() = runUnitTest {
        val vm = buildVm(
            identity = FakeIdentityRepository(
                deleteOutcome = DeleteAccountOutcome.NetworkError(RuntimeException("offline")),
            ),
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(DeleteAccountAction.DismissError)
        val s = vm.state
        assertNull(s.error)
        assertEquals(
            "delete", s.confirmationInput,
            "dismissing the error must not wipe the user's typed confirmation",
        )
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        identity: FakeIdentityRepository = FakeIdentityRepository(),
        appCache: FakeAppCache = FakeAppCache(),
    ): DeleteAccountViewModel = DeleteAccountViewModel(
        identityRepository = identity,
        appCache = appCache,
    )
}
