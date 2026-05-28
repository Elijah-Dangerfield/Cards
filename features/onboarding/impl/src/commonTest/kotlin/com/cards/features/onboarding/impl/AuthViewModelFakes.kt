package com.dangerfield.cards.features.onboarding.impl

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
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pluggable in-memory [AuthRepository] for unit-testing the onboarding
 * ViewModels. Construct with the outcomes the test under test cares about
 * (per-method default = the "Unknown" failure case, so any code path you
 * forget to stub fails loudly instead of silently passing).
 *
 * The methods that the auth ViewModels never call are `error()`-stubbed
 * — if a future refactor reaches for them the test fails with a clear
 * message rather than a NPE.
 */
internal class FakeAuthRepository(
    val signInOutcome: SignInOutcome = SignInOutcome.Unknown(RuntimeException("not stubbed")),
    val signUpOutcome: SignUpOutcome = SignUpOutcome.Unknown(RuntimeException("not stubbed")),
    val refreshOutcome: RefreshOutcome = RefreshOutcome.Unknown(RuntimeException("not stubbed")),
    val resendOutcome: ResendOutcome = ResendOutcome.Unknown(RuntimeException("not stubbed")),
    val sendResetOutcome: SendResetOutcome = SendResetOutcome.Unknown(RuntimeException("not stubbed")),
    val oauthSignInOutcome: SignInOutcome = SignInOutcome.Unknown(RuntimeException("not stubbed")),
    val linkEmailOutcome: LinkEmailIdentityOutcome = LinkEmailIdentityOutcome.Unknown(RuntimeException("not stubbed")),
    initialAuthState: AuthState = AuthState.Unauthenticated(),
) : AuthRepository {
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
    var sendResetCalls: Int = 0
        private set
    var lastSendResetEmail: String? = null
        private set
    var oauthSignInCalls: Int = 0
        private set
    var lastOAuthProvider: OAuthProvider? = null
        private set
    var linkEmailCalls: Int = 0
        private set
    var lastLinkEmailArgs: Pair<String, String>? = null
        private set

    private val state = MutableStateFlow(initialAuthState)

    override suspend fun current(): AuthState = state.value
    override fun observe(): Flow<AuthState> = state
    override suspend fun retry(): AuthState = state.value

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

    override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome {
        sendResetCalls += 1
        lastSendResetEmail = email
        return sendResetOutcome
    }

    override suspend fun signOut() { /* not used here */ }

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

/** Empty [AppConfigMap] — keeps IdentityFeatureConfig at its defaults
 *  (both OAuth flags off) so the SignIn ViewModel's initial state has
 *  predictable values across tests. */
internal class EmptyAppConfigMap : AppConfigMap() {
    override val map: Map<String, *> = emptyMap<String, Any>()
}

/** Convenience for tests that need an Authenticated state. */
internal val sampleAuthenticated = AuthState.Authenticated(
    userId = "11111111-1111-1111-1111-111111111111",
    isAnonymous = false,
    email = null,
)

internal val sampleAnonymous = AuthState.Authenticated(
    userId = "anon-1",
    isAnonymous = true,
    email = null,
)
