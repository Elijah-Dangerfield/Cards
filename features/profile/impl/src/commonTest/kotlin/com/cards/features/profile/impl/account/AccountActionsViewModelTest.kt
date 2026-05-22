package com.dangerfield.cards.features.profile.impl.account

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.OAuthProvider
import com.dangerfield.cards.libraries.identity.RefreshOutcome
import com.dangerfield.cards.libraries.identity.ResendOutcome
import com.dangerfield.cards.libraries.identity.SignInOutcome
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val identity = GatedSignOutIdentity(gate)
        val appCache = FakeAppCache()
        val vm = AccountActionsViewModel(
            identityRepository = identity,
            appCache = appCache,
            appScope = AppCoroutineScope(dispatchers),
        )

        vm.takeAction(AccountActionsAction.ConfirmSignOut)
        runCurrent()
        assertEquals(1, identity.signOutStarted, "signOut should be in-flight")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, identity.signOutFinished, "signOut must complete despite VM teardown")
        assertEquals(false, appCache.get().hasUserOnboarded, "onboarding flag must flip after signOut")
    }
}

private class GatedSignOutIdentity(
    private val gate: CompletableDeferred<Unit>,
) : IdentityRepository {
    var signOutStarted: Int = 0
        private set
    var signOutFinished: Int = 0
        private set

    private val _state = MutableStateFlow<IdentityState>(IdentityState.Unknown)
    override val state: StateFlow<IdentityState> = _state

    override suspend fun signOut() {
        signOutStarted += 1
        gate.await()
        signOutFinished += 1
    }

    override suspend fun ensureInitialized(): Identity = error("unused")
    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
    override suspend fun refreshSession(): RefreshOutcome = error("unused")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("unused")
    override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
}
