package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthUnready
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The offline-cold-boot scenario, end to end through the real pieces: real
 * [AuthGateImpl] + real [GuestSessionHealer] + the real `authedCall` boundary
 * over a request-counting MockEngine. Pins the three promises of the system:
 * an offline stranded guest gets an honest Offline failure with ZERO wire hits
 * (no phantom 401s); regaining connectivity heals on the next attempt and the
 * retry then fires for real; and non-healable states (signed out) never mint.
 * Fine-grained verdict rows live in AuthGateImplTest; boundary mechanics in
 * networking's NetworkCallTest.
 */
class AuthReadinessScenarioTest : CoroutineTest() {

    @Test
    fun offlineColdBoot_asOwedGuest_failsOffline_withZeroWireHits() = runUnitTest {
        val h = build(onboarded = true, offline = true)
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        val result = h.network.authedCall("rooms.create") { http -> http.get("/v1/rooms") }

        val failure = assertIs<AuthUnready>(result.exceptionOrNull())
        assertEquals(AuthReason.Offline, failure.reason)
        assertEquals(0, h.network.requestCount, "a doomed offline call must never fire — no phantom 401s")
        assertEquals(0, h.creator.ensureCalls, "offline is not healable")
    }

    @Test
    fun connectivityRegained_healsOnNextAttempt_thenRetrySucceeds() = runUnitTest {
        val h = build(onboarded = true, offline = true)
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        // Offline attempt: honest failure, nothing fired.
        assertIs<AuthUnready>(h.network.authedCall("rooms.create") { http -> http.get("/v1/rooms") }.exceptionOrNull())

        // Back online. The next attempt is honestly "finishing setup" NOW and
        // kicks the healer in the background.
        h.offline.value = false
        val healing = h.network.authedCall("rooms.create") { http -> http.get("/v1/rooms") }
        assertEquals(AuthReason.FinishingSetup, assertIs<AuthUnready>(healing.exceptionOrNull()).reason)
        assertEquals(0, h.network.requestCount)

        advanceUntilIdle() // heal lands: creator mints, auth flips Authenticated

        assertEquals(1, h.creator.ensureCalls, "the heal ran exactly once")
        val retry = h.network.authedCall("rooms.create") { http -> http.get("/v1/rooms").bodyAsText() }
        assertEquals("ok", retry.getOrNull())
        assertEquals(1, h.network.requestCount, "the retry fires for real once healed")
    }

    @Test
    fun signedOut_readsNeedAccount_andNeverMints() = runUnitTest {
        val h = build(onboarded = true, offline = false)
        h.auth.emit(AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.SignedOut))
        advanceUntilIdle()

        val result = h.network.authedCall("wallet.sync") { http -> http.get("/v1/wallet") }
        advanceUntilIdle()

        assertEquals(AuthReason.NeedAccount, assertIs<AuthUnready>(result.exceptionOrNull()).reason)
        assertEquals(0, h.creator.ensureCalls, "a deliberate sign-out is never resurrected by background traffic")
        assertEquals(0, h.network.requestCount)
    }

    private class Harness(
        val auth: FakeAuthRepository,
        val creator: HealingGuestAccountCreator,
        val offline: MutableStateFlow<Boolean>,
        val network: ScenarioNetworkClient,
    )

    private fun build(onboarded: Boolean, offline: Boolean): Harness {
        val auth = FakeAuthRepository()
        val creator = HealingGuestAccountCreator(auth)
        val offlineFlow = MutableStateFlow(offline)
        val appCache = FakeAppCache(onboarded)
        val appState = FakeAppState(offlineFlow)
        val scope = AppCoroutineScope(dispatchers)
        val healer = GuestSessionHealer(
            authRepositoryProvider = { auth },
            guestAccountCreatorProvider = { creator },
            profileRepositoryProvider = { FakeProfileRepository() },
            appCache = appCache,
            appState = appState,
            appScope = scope,
        )
        val gate = AuthGateImpl(
            authRepository = auth,
            guestAccountCreator = creator,
            appCache = appCache,
            appState = appState,
            healer = healer,
            appScope = scope,
        )
        return Harness(auth, creator, offlineFlow, ScenarioNetworkClient(gate))
    }

    @OptIn(InternalNetworkingApi::class)
    private class ScenarioNetworkClient(private val gate: AuthGateImpl) : NetworkClient {
        var requestCount = 0
            private set

        private val engine = MockEngine {
            requestCount++
            respond("ok")
        }

        override val client: HttpClient by lazy { HttpClient(engine) }
        override val authenticatedClient: HttpClient by lazy { HttpClient(engine) }
        override suspend fun awaitAuthReady() = Unit
        override suspend fun authVerdict(requirement: AuthRequirement): AuthVerdict =
            gate.awaitVerdict(requirement)
    }

    /** Mints like the real creator: flips auth to Authenticated so the identity fan-out story holds. */
    private class HealingGuestAccountCreator(
        private val auth: FakeAuthRepository,
    ) : GuestAccountCreator {
        var ensureCalls = 0
            private set

        override val state = MutableStateFlow<AccountCreationState>(AccountCreationState.Idle)
        override fun start(identity: PendingIdentity) = error("unused")
        override fun retry() = error("unused")
        override suspend fun awaitTerminal(): AccountCreationState = state.value
        override suspend fun ensureSession(fallbackIdentity: PendingIdentity): AccountCreationState {
            ensureCalls++
            auth.emit(AuthState.Authenticated(userId = "healed", isAnonymous = true, email = null))
            return AccountCreationState.Succeeded
        }
    }

    private class FakeAppState(
        override val isOffline: StateFlow<Boolean>,
    ) : AppState {
        override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private class FakeAppCache(onboarded: Boolean) : AppCache {
        private val state = MutableStateFlow(AppData(hasUserOnboarded = onboarded))
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class FakeProfileRepository : ProfileRepository {
        override suspend fun current(): Profile = Profile.Fallback(id = "local")
        override fun observe(): Flow<Profile> = emptyFlow()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    }

    private class FakeAuthRepository : AuthRepository {
        private val flow = MutableSharedFlow<AuthState>(replay = 1, extraBufferCapacity = 4)
        private var latest: AuthState = AuthState.Unauthenticated()

        fun emit(state: AuthState) {
            latest = state
            flow.tryEmit(state)
        }

        override fun observe(): Flow<AuthState> = flow
        override suspend fun current(): AuthState = latest
        override suspend fun retry(): AuthState = latest
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
        override suspend fun refreshSession(): RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = error("unused")
        override suspend fun signOut() = error("unused")
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome = error("unused")
    }
}
