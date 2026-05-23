package com.dangerfield.cards.features.profile.impl.account

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.config.AppConfigMap
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
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pluggable in-memory [AuthRepository] for unit-testing the account
 * ViewModels (Delete + Claim). Methods the account VMs never touch are
 * `error()`-stubbed so a future refactor reaching for them fails loudly
 * instead of silently passing.
 *
 * Defaults: every outcome is `Unknown` so a test that forgets to stub the
 * branch it cares about fails at the assertion instead of getting a
 * misleading "no error" pass.
 */
internal class FakeAuthRepository(
    val deleteOutcome: DeleteAccountOutcome = DeleteAccountOutcome.Unknown(RuntimeException("not stubbed")),
    val linkOutcome: LinkIdentityOutcome = LinkIdentityOutcome.Unknown(RuntimeException("not stubbed")),
    val oauthSignInOutcome: SignInOutcome = SignInOutcome.Unknown(RuntimeException("not stubbed")),
) : AuthRepository {

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

    private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated())

    override suspend fun current(): AuthState = state.value
    override fun observe(): Flow<AuthState> = state
    override suspend fun accessToken(): String? = null
    override suspend fun refreshAccessToken(): String? = null
    override suspend fun retry(): AuthState = state.value

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        error("signInWithEmail not used by the account ViewModels")

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        error("signUpWithEmail not used by the account ViewModels")

    override suspend fun refreshSession(): RefreshOutcome =
        error("refreshSession not used by the account ViewModels")

    override suspend fun resendVerificationEmail(email: String): ResendOutcome =
        error("resendVerificationEmail not used by the account ViewModels")

    override suspend fun signOut() { /* not used here */ }

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
 * Pluggable in-memory [ProfileRepository] for unit-testing VMs that
 * read profile data (Edit, Feedback, BugReport).
 */
internal class FakeProfileRepository(
    initial: Profile? = null,
    private val updateOutcome: UpdateProfileOutcome = UpdateProfileOutcome.Unknown(RuntimeException("not stubbed")),
    private val avatarPackOutcome: AvatarPackOutcome = AvatarPackOutcome.Success(emptyList()),
) : ProfileRepository {
    private val flow = MutableSharedFlow<Profile>(replay = 1, extraBufferCapacity = 8)

    init {
        if (initial != null) flow.tryEmit(initial)
    }

    var updateCalls: Int = 0
        private set
    var lastUpdateArgs: Quadruple? = null
        private set

    fun emit(profile: Profile) {
        flow.tryEmit(profile)
    }

    override suspend fun current(): Profile = flow.replayCache.firstOrNull()
        ?: error("Profile.current called before any value emitted")

    override fun observe(): Flow<Profile> = flow

    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome {
        updateCalls += 1
        lastUpdateArgs = Quadruple(displayName, avatarEmoji, avatarBackgroundColor, clearAvatarBackgroundColor)
        return updateOutcome
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome = avatarPackOutcome

    data class Quadruple(
        val displayName: String?,
        val avatarEmoji: String?,
        val avatarBackgroundColor: String?,
        val clearAvatarBackgroundColor: Boolean,
    )
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

internal val sampleProfile = Profile.Authenticated(
    id = "11111111-1111-1111-1111-111111111111",
    displayName = "QuietAce72",
    avatarEmoji = "🃏",
    avatarBackgroundColor = null,
    email = null,
    isAnonymous = false,
)
