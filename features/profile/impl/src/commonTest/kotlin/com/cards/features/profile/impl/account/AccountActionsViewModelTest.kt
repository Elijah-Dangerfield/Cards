package com.dangerfield.cards.features.profile.impl.account

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the fire-and-forget contract for sign-out: the server-side
 * `signOut` call (and the subsequent AppCache flip back to "not
 * onboarded") must complete even if the user navigates away while
 * the VM is showing the `isSigningOut = true` state.
 */
class AccountActionsViewModelTest : CoroutineTest() {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmSignOut_signOutCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<Unit>()
        val auth = GatedSignOutAuthRepo(gate)
        val appCache = FakeAppCache()
        val vm = AccountActionsViewModel(
            authRepository = auth,
            appCache = appCache,
            appScope = AppCoroutineScope(dispatchers),
        )

        vm.takeAction(AccountActionsAction.ConfirmSignOut)
        runCurrent()
        assertEquals(1, auth.signOutStarted, "signOut should be in-flight")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, auth.signOutFinished, "signOut must complete despite VM teardown")
        assertEquals(false, appCache.get().hasUserOnboarded, "onboarding flag must flip after signOut")
    }
}

/** Gates [signOut] on a caller-controlled latch so the test can assert the call
 *  survives VM teardown. */
private class GatedSignOutAuthRepo(
    private val gate: CompletableDeferred<Unit>,
) : StubAuthRepository() {
    var signOutStarted: Int = 0
        private set
    var signOutFinished: Int = 0
        private set

    override suspend fun signOut() {
        signOutStarted += 1
        gate.await()
        signOutFinished += 1
    }
}
