package com.dangerfield.cards.features.profile.impl.feedback

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.profile.impl.account.FakeAppCache
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.Router
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the email-pre-fill contract for the Feedback screen: an
 * authenticated profile's email seeds the initial state asynchronously
 * (via the VM's init block + ProfileRepository.current()) so the user
 * doesn't have to retype it; anon profiles with null email and the
 * not-yet-resolved race window both leave the field blank.
 */
class FeedbackViewModelTest : CoroutineTest() {

    @Test
    fun seed_prefillsEmailFromAuthenticatedProfile() = runUnitTest {
        val vm = buildVm(
            profile = StubProfile(authenticatedWith(email = "alice@example.com")),
        )
        runCurrent()
        assertEquals("alice@example.com", vm.state.email)
    }

    @Test
    fun seed_anonProfileWithoutEmail_leavesFieldBlank() = runUnitTest {
        val vm = buildVm(profile = StubProfile(authenticatedWith(email = null)))
        runCurrent()
        assertEquals("", vm.state.email)
    }

    @Test
    fun seed_profileNotYetResolved_leavesFieldBlank() = runUnitTest {
        val vm = buildVm(profile = StubProfile(initial = null))
        runCurrent()
        assertEquals("", vm.state.email)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_serverCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val repository = ControllableFeedbackRepository(gate)
        val vm = buildVm(
            profile = StubProfile(authenticatedWith(email = "alice@example.com")),
            repository = repository,
        )
        runCurrent()
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
        profile: ProfileRepository,
        repository: FeedbackRepository = NoopFeedbackRepository,
    ): FeedbackViewModel = FeedbackViewModel(
        repository = repository,
        router = NoopRouter,
        appCache = FakeAppCache(),
        appScope = AppCoroutineScope(dispatchers),
        profileRepository = profile,
    )
}

internal fun authenticatedWith(email: String?): Profile.Authenticated = Profile.Authenticated(
    id = "u1",
    displayName = "Alice",
    avatarEmoji = "🃏",
    avatarBackgroundColor = null,
    isAnonymous = email == null,
    email = email,
    createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
)

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

internal object NoopRouter : Router {
    override fun navigate(route: Route, options: NavigationOptions) = Unit
    override fun goBack() = Unit
    override fun popBackTo(route: Route, inclusive: Boolean) = Unit
    override fun switchTab(route: Route) = Unit
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

internal class StubProfile(initial: Profile? = null) : ProfileRepository {
    private val flow = MutableSharedFlow<Profile>(replay = 1, extraBufferCapacity = 1)

    init {
        if (initial != null) flow.tryEmit(initial)
    }

    override suspend fun current(): Profile = flow.replayCache.firstOrNull() ?: flow.first()

    override fun observe(): Flow<Profile> = flow

    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("unused")

    override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
}
