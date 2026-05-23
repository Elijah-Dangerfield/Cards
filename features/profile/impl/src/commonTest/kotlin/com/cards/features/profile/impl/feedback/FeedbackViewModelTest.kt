package com.dangerfield.cards.features.profile.impl.feedback

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.profile.impl.account.FakeAppCache
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.OAuthProvider
import com.dangerfield.cards.libraries.identity.RefreshOutcome
import com.dangerfield.cards.libraries.identity.ResendOutcome
import com.dangerfield.cards.libraries.identity.SignInOutcome
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.Router
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the email-pre-fill contract for the Feedback screen: a claimed
 * identity's email seeds the initial state so the user doesn't have to
 * retype it; anon identities and the not-yet-resolved race window both
 * leave the field blank.
 */
class FeedbackViewModelTest : CoroutineTest() {

    @Test
    fun initialState_prefillsEmailFromClaimedIdentity() = runUnitTest {
        val vm = buildVm(
            identity = StubIdentity(
                signedInWith(email = "alice@example.com"),
            ),
        )
        assertEquals("alice@example.com", vm.state.email)
    }

    @Test
    fun initialState_anonIdentityWithoutEmail_leavesFieldBlank() = runUnitTest {
        val vm = buildVm(
            identity = StubIdentity(signedInWith(email = null)),
        )
        assertEquals("", vm.state.email)
    }

    @Test
    fun initialState_identityNotYetResolved_leavesFieldBlank() = runUnitTest {
        val vm = buildVm(identity = StubIdentity(IdentityState.Unknown))
        assertEquals("", vm.state.email)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_serverCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val repository = ControllableFeedbackRepository(gate)
        val vm = buildVm(
            identity = StubIdentity(IdentityState.SignedIn(sampleIdentity(email = "alice@example.com"))),
            repository = repository,
        )
        vm.takeAction(FeedbackAction.MessageChanged("found a bug"))
        vm.takeAction(FeedbackAction.Submit)
        runCurrent()
        assertEquals(1, repository.submitStarted, "submitFeedback should be in-flight")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(Result.success(Unit))
        runCurrent()
        assertEquals(1, repository.submitFinished, "submitFeedback must complete despite VM teardown")
    }

    private fun buildVm(
        identity: IdentityRepository,
        repository: FeedbackRepository = NoopFeedbackRepository,
    ): FeedbackViewModel = FeedbackViewModel(
        repository = repository,
        router = NoopRouter,
        appCache = FakeAppCache(),
        appScope = AppCoroutineScope(dispatchers),
        identityRepository = identity,
    )

    private fun sampleIdentity(email: String?) = Identity(
        userId = "u1",
        displayName = "Alice",
        avatarEmoji = "🃏",
        avatarBackgroundColor = null,
        isAnonymous = email == null,
        email = email,
    )
}

/**
 * Variant of [NoopFeedbackRepository] that gates `submitFeedback` on an
 * external [CompletableDeferred] so a test can observe whether the call
 * actually finished (vs. being cancelled mid-flight).
 */
internal class ControllableFeedbackRepository(
    private val gate: CompletableDeferred<Result<Unit>>,
) : FeedbackRepository {
    var submitStarted: Int = 0
        private set
    var submitFinished: Int = 0
        private set

    override suspend fun submitFeedback(
        message: String,
        isBugReport: Boolean,
        logId: String?,
        errorCode: Int?,
        email: String?,
    ): Result<Unit> {
        submitStarted += 1
        val outcome = gate.await()
        submitFinished += 1
        return outcome
    }
}

private fun signedInWith(email: String?): IdentityState =
    IdentityState.SignedIn(
        Identity(
            userId = "u1",
            displayName = "Alice",
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            isAnonymous = email == null,
            email = email,
        ),
    )

internal object NoopRouter : Router {
    override fun navigate(route: Route, options: NavigationOptions) = Unit
    override fun goBack() = Unit
    override fun popBackTo(route: Route, inclusive: Boolean) = Unit
    override fun openWebLink(url: String) = Unit
}

internal object NoopFeedbackRepository : FeedbackRepository {
    override suspend fun submitFeedback(
        message: String,
        isBugReport: Boolean,
        logId: String?,
        errorCode: Int?,
        email: String?,
    ): Result<Unit> = Result.success(Unit)
}

internal class StubIdentity(initial: IdentityState) : IdentityRepository {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<IdentityState> = _state

    override suspend fun ensureInitialized(): Identity = error("unused")
    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
    override suspend fun refreshSession(): RefreshOutcome = error("unused")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
    override suspend fun signOut() = Unit
    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("unused")
    override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome = error("unused")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
}
