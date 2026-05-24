package com.dangerfield.cards.features.profile.impl.account

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [DeleteAccountViewModel]'s outcome → state mapping.
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
        val auth = FakeAuthRepository()
        val vm = buildVm(auth = auth)
        vm.takeAction(DeleteAccountAction.ConfirmDelete) // empty input
        assertEquals(0, auth.deleteCalls, "short-circuit before any network call")
    }

    @Test
    fun submit_success_flipsOnboardedFalse_andEmitsDeleted() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = buildVm(
            auth = FakeAuthRepository(deleteOutcome = DeleteAccountOutcome.Success),
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
            auth = FakeAuthRepository(deleteOutcome = DeleteAccountOutcome.NotSignedIn),
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
    fun submit_anonymousNotAllowed_surfacesAnonMessage_andKeepsOnboardingFlag() = runUnitTest {
        // The UI gate should keep anon users out of the Delete screen
        // entirely, but a deep-link / future regression could land them
        // here. The outcome must render a real message, not silently
        // succeed.
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = buildVm(
            auth = FakeAuthRepository(deleteOutcome = DeleteAccountOutcome.AnonymousNotAllowed),
            appCache = cache,
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(false, last.isSubmitting)
            assertTrue(
                last.error!!.contains("anonymous", ignoreCase = true),
                "expected an anonymous-shaped error, got: ${last.error}",
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            true, cache.get().hasUserOnboarded,
            "anon delete is rejected; onboarding flag stays as-was",
        )
    }

    @Test
    fun submit_notConfigured_surfacesNotAvailable_andKeepsOnboardingFlag() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(hasUserOnboarded = true))
        val vm = buildVm(
            auth = FakeAuthRepository(deleteOutcome = DeleteAccountOutcome.NotConfigured),
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
            auth = FakeAuthRepository(
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
            auth = FakeAuthRepository(
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
            auth = FakeAuthRepository(
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

        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete!"))
        val s = vm.state
        assertNull(s.error, "typing into the field clears the inline error")
        assertEquals("delete!", s.confirmationInput, "input value is preserved verbatim")
    }

    @Test
    fun dismissError_clearsError_withoutMutatingInput() = runUnitTest {
        val vm = buildVm(
            auth = FakeAuthRepository(
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_deleteCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<DeleteAccountOutcome>()
        val auth = GatedDeleteAuth(gate)
        val vm = DeleteAccountViewModel(
            authRepository = auth,
            appCache = FakeAppCache(),
            appScope = AppCoroutineScope(dispatchers),
        )
        vm.takeAction(DeleteAccountAction.ConfirmationChanged("delete"))
        vm.takeAction(DeleteAccountAction.ConfirmDelete)
        runCurrent()
        assertEquals(1, auth.deleteStarted, "deleteAccount should be in-flight")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(DeleteAccountOutcome.Success)
        runCurrent()
        assertEquals(1, auth.deleteFinished, "deleteAccount must complete despite VM teardown")
    }

    private fun buildVm(
        auth: FakeAuthRepository = FakeAuthRepository(),
        appCache: FakeAppCache = FakeAppCache(),
    ): DeleteAccountViewModel = DeleteAccountViewModel(
        authRepository = auth,
        appCache = appCache,
        appScope = AppCoroutineScope(dispatchers),
    )
}

private class GatedDeleteAuth(
    private val gate: CompletableDeferred<DeleteAccountOutcome>,
) : AuthRepository {
    var deleteStarted: Int = 0
        private set
    var deleteFinished: Int = 0
        private set

    private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated())
    override suspend fun current(): AuthState = state.value
    override fun observe(): Flow<AuthState> = state
    override suspend fun retry(): AuthState = state.value

    override suspend fun deleteAccount(): DeleteAccountOutcome {
        deleteStarted += 1
        val outcome = gate.await()
        deleteFinished += 1
        return outcome
    }

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
    override suspend fun refreshSession(): RefreshOutcome = error("unused")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
    override suspend fun signOut() = Unit
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome = error("unused")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
}
