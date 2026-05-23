package com.dangerfield.cards.features.profile.impl.account

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
 * Pluggable in-memory [IdentityRepository] for unit-testing the account
 * ViewModels (Delete + Claim). Mirrors the onboarding `FakeIdentityRepository`
 * pattern — methods the account VMs never touch are `error()`-stubbed so a
 * future refactor reaching for them fails loudly instead of silently passing.
 *
 * Defaults: every outcome is `Unknown` so a test that forgets to stub the
 * branch it cares about fails at the assertion instead of getting a
 * misleading "no error" pass.
 */
internal class FakeIdentityRepository(
    val deleteOutcome: DeleteAccountOutcome = DeleteAccountOutcome.Unknown(RuntimeException("not stubbed")),
    val linkOutcome: LinkIdentityOutcome = LinkIdentityOutcome.Unknown(RuntimeException("not stubbed")),
    val oauthSignInOutcome: SignInOutcome = SignInOutcome.Unknown(RuntimeException("not stubbed")),
) : IdentityRepository {

    var deleteCalls: Int = 0
        private set
    var linkCalls: Int = 0
        private set
    var lastLinkProvider: OAuthProvider? = null
        private set
    var oauthSignInCalls: Int = 0
        private set
    var lastOAuthProvider: OAuthProvider? = null
        private set

    private val identityState =
        MutableStateFlow<IdentityState>(IdentityState.Unknown)

    override val state: kotlinx.coroutines.flow.StateFlow<IdentityState> = identityState

    override suspend fun ensureInitialized(): Identity =
        error("ensureInitialized not used by the account ViewModels")

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        error("signInWithEmail not used by the account ViewModels")

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        error("signUpWithEmail not used by the account ViewModels")

    override suspend fun refreshSession(): RefreshOutcome =
        error("refreshSession not used by the account ViewModels")

    override suspend fun resendVerificationEmail(email: String): ResendOutcome =
        error("resendVerificationEmail not used by the account ViewModels")

    override suspend fun signOut() { /* not used here */ }

    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("updateProfile not used by the account ViewModels")

    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        error("fetchAvatarPack not used by the account ViewModels")

    override suspend fun deleteAccount(): DeleteAccountOutcome {
        deleteCalls += 1
        return deleteOutcome
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome {
        linkCalls += 1
        lastLinkProvider = provider
        return linkOutcome
    }

    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
        error("linkEmailIdentity not used by the account ViewModels")

    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome {
        oauthSignInCalls += 1
        lastOAuthProvider = provider
        return oauthSignInOutcome
    }
}

/**
 * In-memory [AppCache] that records writes — used by [DeleteAccountViewModel]
 * to flip `hasUserOnboarded` back to false after a successful delete.
 */
internal class FakeAppCache(initial: AppData = AppData(hasUserOnboarded = true)) : AppCache {
    private val state = MutableStateFlow(initial)
    override val updates: Flow<AppData> = state
    override suspend fun get(): AppData = state.value
    override suspend fun set(value: AppData) { state.value = value }
    override suspend fun clear() { state.value = AppData() }
}

/** Configurable [AppConfigMap] — [ClaimAccountViewModel] reads the OAuth
 *  feature flags off this map at init. `featureName.propertyName` paths
 *  are split on `.` and resolved through nested maps, so a flat map with
 *  the dotted key wouldn't be found. */
internal class TestAppConfigMap(
    private val backing: Map<String, Any> = emptyMap(),
) : AppConfigMap() {
    override val map: Map<String, *> = backing

    companion object {
        /** OAuth-enabled map used by ClaimAccountViewModel tests that exercise
         *  provider buttons. Keys must match `FeatureConfig`'s `featureName +
         *  propertyName` path resolution. */
        fun withOAuthEnabled(google: Boolean = true, apple: Boolean = true) = TestAppConfigMap(
            backing = mapOf(
                "identity" to buildMap {
                    if (google) put("googleSignInEnabled", true)
                    if (apple) put("appleSignInEnabled", true)
                },
            ),
        )
    }
}

internal val sampleIdentity = Identity(
    userId = "11111111-1111-1111-1111-111111111111",
    displayName = "QuietAce72",
    avatarEmoji = "🃏",
    avatarBackgroundColor = null,
    isAnonymous = false,
)
