package com.dangerfield.cards.features.profile.impl.account

import androidx.lifecycle.viewModelScope
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

private class GatedSignOutAuthRepo(
    private val gate: CompletableDeferred<Unit>,
) : AuthRepository {
    var signOutStarted: Int = 0
        private set
    var signOutFinished: Int = 0
        private set

    private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated())

    override suspend fun current(): AuthState = state.value
    override fun observe(): Flow<AuthState> = state
    override suspend fun retry(): AuthState = state.value

    override suspend fun signOut() {
        signOutStarted += 1
        gate.await()
        signOutFinished += 1
    }

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
    override suspend fun refreshSession(): RefreshOutcome = error("unused")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
    override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome = error("unused")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
}
