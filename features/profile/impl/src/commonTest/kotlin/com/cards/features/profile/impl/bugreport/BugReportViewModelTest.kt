package com.dangerfield.cards.features.profile.impl.bugreport

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.profile.impl.account.FakeAppCache
import com.dangerfield.cards.features.profile.impl.feedback.ControllableFeedbackRepository
import com.dangerfield.cards.features.profile.impl.feedback.FeedbackRepository
import com.dangerfield.cards.features.profile.impl.feedback.NoopFeedbackRepository
import com.dangerfield.cards.features.profile.impl.feedback.NoopRouter
import com.dangerfield.cards.features.profile.impl.feedback.StubIdentity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the email-pre-fill contract for the Bug Report screen: parallel
 * to the Feedback path. Other initial fields (logId / errorCode /
 * contextMessage) keep their existing assisted-injection plumbing.
 */
class BugReportViewModelTest : CoroutineTest() {

    @Test
    fun initialState_prefillsEmailFromClaimedIdentity() = runUnitTest {
        val vm = buildVm(
            identity = StubIdentity(
                IdentityState.SignedIn(
                    sampleIdentity(email = "alice@example.com"),
                ),
            ),
        )
        assertEquals("alice@example.com", vm.state.email)
    }

    @Test
    fun initialState_anonIdentity_leavesFieldBlank() = runUnitTest {
        val vm = buildVm(
            identity = StubIdentity(IdentityState.SignedIn(sampleIdentity(email = null))),
        )
        assertEquals("", vm.state.email)
    }

    @Test
    fun initialState_carriesAssistedContextThrough() = runUnitTest {
        val vm = buildVm(
            identity = StubIdentity(IdentityState.Unknown),
            logId = "log-42",
            errorCode = 500,
            contextMessage = "boom",
        )
        assertEquals("log-42", vm.state.logId)
        assertEquals(500, vm.state.errorCode)
        assertEquals("boom", vm.state.contextMessage)
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
        vm.takeAction(BugReportAction.MessageChanged("crash on tap"))
        vm.takeAction(BugReportAction.Submit)
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
        logId: String? = null,
        errorCode: Int? = null,
        contextMessage: String? = null,
    ): BugReportViewModel = BugReportViewModel(
        repository = repository,
        router = NoopRouter,
        appCache = FakeAppCache(),
        appScope = AppCoroutineScope(dispatchers),
        identityRepository = identity,
        logId = logId,
        errorCode = errorCode,
        contextMessage = contextMessage,
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
