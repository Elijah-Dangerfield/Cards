package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.config.AppConfigMap
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pluggable in-memory [IdentityRepository] for unit-testing the onboarding
 * ViewModels. Construct with the outcomes the test under test cares about
 * (per-method default = the "Unknown" failure case, so any code path you
 * forget to stub fails loudly instead of silently passing).
 *
 * The methods that the auth ViewModels never call are `error()`-stubbed
 * — if a future refactor reaches for them the test fails with a clear
 * message rather than a NPE.
 */
internal class FakeIdentityRepository(
    val signInOutcome: SignInOutcome = SignInOutcome.Unknown(RuntimeException("not stubbed")),
    val signUpOutcome: SignUpOutcome = SignUpOutcome.Unknown(RuntimeException("not stubbed")),
    val refreshOutcome: RefreshOutcome = RefreshOutcome.Unknown(RuntimeException("not stubbed")),
    val resendOutcome: ResendOutcome = ResendOutcome.Unknown(RuntimeException("not stubbed")),
    val oauthSignInOutcome: SignInOutcome = SignInOutcome.Unknown(RuntimeException("not stubbed")),
    val linkEmailOutcome: LinkEmailIdentityOutcome = LinkEmailIdentityOutcome.Unknown(RuntimeException("not stubbed")),
    initialIdentityState: IdentityState = IdentityState.Unknown,
) : IdentityRepository {
    var signInCalls: Int = 0
        private set
    var lastSignInArgs: Pair<String, String>? = null
        private set
    var signUpCalls: Int = 0
        private set
    var lastSignUpArgs: Pair<String, String>? = null
        private set
    var refreshCalls: Int = 0
        private set
    var resendCalls: Int = 0
        private set
    var lastResendEmail: String? = null
        private set
    var oauthSignInCalls: Int = 0
        private set
    var lastOAuthProvider: OAuthProvider? = null
        private set
    var linkEmailCalls: Int = 0
        private set
    var lastLinkEmailArgs: Pair<String, String>? = null
        private set

    private val identityState =
        MutableStateFlow<IdentityState>(initialIdentityState)

    override val state: kotlinx.coroutines.flow.StateFlow<IdentityState> = identityState

    override suspend fun ensureInitialized(): Identity =
        error("ensureInitialized not used by the auth ViewModels")

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome {
        signInCalls += 1
        lastSignInArgs = email to password
        return signInOutcome
    }

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome {
        signUpCalls += 1
        lastSignUpArgs = email to password
        return signUpOutcome
    }

    override suspend fun refreshSession(): RefreshOutcome {
        refreshCalls += 1
        return refreshOutcome
    }

    override suspend fun resendVerificationEmail(email: String): ResendOutcome {
        resendCalls += 1
        lastResendEmail = email
        return resendOutcome
    }

    override suspend fun signOut() { /* not used here */ }

    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("updateProfile not used by the auth ViewModels")

    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        error("fetchAvatarPack not used by the auth ViewModels")

    override suspend fun deleteAccount(): DeleteAccountOutcome =
        error("deleteAccount not used by the auth ViewModels")

    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
        error("linkOAuthIdentity not used by the auth ViewModels")

    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome {
        linkEmailCalls += 1
        lastLinkEmailArgs = email to password
        return linkEmailOutcome
    }

    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome {
        oauthSignInCalls += 1
        lastOAuthProvider = provider
        return oauthSignInOutcome
    }
}

/**
 * In-memory [AppCache] that records writes — used to assert the
 * `hasUserOnboarded = true` side effect on successful sign-in / link.
 */
internal class FakeAppCache(initial: AppData = AppData()) : AppCache {
    private val state = MutableStateFlow(initial)
    override val updates: Flow<AppData> = state
    override suspend fun get(): AppData = state.value
    override suspend fun set(value: AppData) { state.value = value }
    override suspend fun clear() { state.value = AppData() }
}

/**
 * Base class for test doubles that override exactly the method under
 * test and leave everything else stubbed. Avoids 100+ lines of boilerplate
 * in every test that only needs one method.
 *
 * Each suspend method calls `error("not used in this test")` — if a
 * future refactor reaches for one that isn't overridden the test fails
 * loudly with a precise message.
 */
internal open class NoOpIdentityRepository : IdentityRepository {
    override val state: kotlinx.coroutines.flow.StateFlow<IdentityState> =
        MutableStateFlow(IdentityState.Unknown)

    override suspend fun ensureInitialized(): Identity =
        error("ensureInitialized not stubbed in this test")
    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        error("signInWithEmail not stubbed in this test")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        error("signUpWithEmail not stubbed in this test")
    override suspend fun refreshSession(): RefreshOutcome =
        error("refreshSession not stubbed in this test")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome =
        error("resendVerificationEmail not stubbed in this test")
    override suspend fun signOut() { /* no-op */ }
    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("updateProfile not stubbed in this test")
    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        error("fetchAvatarPack not stubbed in this test")
    override suspend fun deleteAccount(): DeleteAccountOutcome =
        error("deleteAccount not stubbed in this test")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
        error("linkOAuthIdentity not stubbed in this test")
    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
        error("linkEmailIdentity not stubbed in this test")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
        error("signInWithOAuth not stubbed in this test")
}

/** Empty [AppConfigMap] — keeps IdentityFeatureConfig at its defaults
 *  (both OAuth flags off) so the SignIn ViewModel's initial state has
 *  predictable values across tests. */
internal class EmptyAppConfigMap : AppConfigMap() {
    override val map: Map<String, *> = emptyMap<String, Any>()
}

internal val sampleIdentity = Identity(
    userId = "11111111-1111-1111-1111-111111111111",
    displayName = "QuietAce72",
    avatarEmoji = "🃏",
    avatarBackgroundColor = null,
    isAnonymous = false,
)
