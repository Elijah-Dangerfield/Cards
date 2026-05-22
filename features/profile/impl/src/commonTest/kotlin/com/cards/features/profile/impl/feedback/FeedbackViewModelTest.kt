package com.dangerfield.cards.features.profile.impl.feedback

import com.dangerfield.cards.features.profile.impl.account.FakeAppCache
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
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private fun buildVm(
        identity: IdentityRepository,
    ): FeedbackViewModel = FeedbackViewModel(
        repository = NoopFeedbackRepository,
        router = NoopRouter,
        appCache = FakeAppCache(),
        identityRepository = identity,
    )
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
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
}
