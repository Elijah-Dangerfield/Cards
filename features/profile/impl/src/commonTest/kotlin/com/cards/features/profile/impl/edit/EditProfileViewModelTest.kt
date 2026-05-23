package com.dangerfield.cards.features.profile.impl.edit

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AvatarPack
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

class EditProfileViewModelTest : CoroutineTest() {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_updateProfileCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val identity = GatedUpdateProfileIdentity(gate)
        val vm = EditProfileViewModel(
            identityRepository = identity,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.takeAction(EditProfileAction.DisplayNameChanged("NewName"))
        vm.takeAction(EditProfileAction.AvatarSelected("🦊"))
        vm.takeAction(EditProfileAction.Submit)
        runCurrent()
        assertEquals(1, identity.updateStarted, "updateProfile should be in-flight")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(UpdateProfileOutcome.Success(sampleIdentity))
        runCurrent()
        assertEquals(
            1, identity.updateFinished,
            "updateProfile must complete despite VM teardown",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_emitsSavedImmediately_withoutWaitingOnNetwork() = runUnitTest {
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val identity = GatedUpdateProfileIdentity(gate)
        val vm = EditProfileViewModel(
            identityRepository = identity,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(EditProfileAction.DisplayNameChanged("NewName"))
            vm.takeAction(EditProfileAction.AvatarSelected("🦊"))
            vm.takeAction(EditProfileAction.Submit)

            assertEquals(EditProfileEvent.Saved, awaitItem())
            assertEquals(
                1, identity.updateStarted,
                "updateProfile should be in-flight while Saved already emitted",
            )
            assertEquals(
                0, identity.updateFinished,
                "Saved must not block on the network roundtrip",
            )

            gate.complete(UpdateProfileOutcome.Success(sampleIdentity))
        }
    }
}

private val sampleIdentity = Identity(
    userId = "11111111-1111-1111-1111-111111111111",
    displayName = "QuietAce72",
    avatarEmoji = "🃏",
    avatarBackgroundColor = null,
    isAnonymous = false,
)

private class GatedUpdateProfileIdentity(
    private val gate: CompletableDeferred<UpdateProfileOutcome>,
) : IdentityRepository {
    var updateStarted: Int = 0
        private set
    var updateFinished: Int = 0
        private set

    private val _state = MutableStateFlow<IdentityState>(IdentityState.SignedIn(sampleIdentity))
    override val state: StateFlow<IdentityState> = _state

    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome {
        updateStarted += 1
        val outcome = gate.await()
        updateFinished += 1
        return outcome
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        AvatarPackOutcome.Success(
            packs = listOf(AvatarPack(id = "starter", name = "Starter", emojis = listOf("🃏", "🦊"))),
            palette = emptyList(),
        )

    override suspend fun ensureInitialized(): Identity = error("unused")
    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
    override suspend fun refreshSession(): RefreshOutcome = error("unused")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
}
