package com.dangerfield.cards.features.profile.impl.bugreport

import com.dangerfield.cards.features.profile.impl.account.FakeAppCache
import com.dangerfield.cards.features.profile.impl.feedback.NoopFeedbackRepository
import com.dangerfield.cards.features.profile.impl.feedback.NoopRouter
import com.dangerfield.cards.features.profile.impl.feedback.StubIdentity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
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

    private fun buildVm(
        identity: IdentityRepository,
        logId: String? = null,
        errorCode: Int? = null,
        contextMessage: String? = null,
    ): BugReportViewModel = BugReportViewModel(
        repository = NoopFeedbackRepository,
        router = NoopRouter,
        appCache = FakeAppCache(),
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
