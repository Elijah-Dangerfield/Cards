package com.dangerfield.cards.libraries.navigation.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
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
import com.dangerfield.cards.libraries.identity.auth.StrandedAccount
import com.dangerfield.cards.libraries.identity.auth.StrandedAccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionExpiredViewModelTest : CoroutineTest() {

    @Test
    fun recordedClaimedAccount_isNamed_andSignInIsTheOffer() = runUnitTest {
        val vm = buildViewModel(
            stranded = FakeStrandedAccountStore(
                StrandedAccount(userId = "u1", displayName = "LuckyJack66", email = "a@b.com"),
            ),
        )

        assertEquals(SessionRecovery.SignInToRestore("LuckyJack66"), vm.stateFlow.value.recovery)
    }

    @Test
    fun noRecord_claimedSession_stillOffersSignIn_withoutAName() = runUnitTest {
        val vm = buildViewModel(wasAnonymous = false)

        assertEquals(SessionRecovery.SignInToRestore(displayName = null), vm.stateFlow.value.recovery)
    }

    @Test
    fun recordWithNoName_fallsBackToUnnamedRecoveryCopy() = runUnitTest {
        val vm = buildViewModel(
            stranded = FakeStrandedAccountStore(StrandedAccount(userId = "u1", displayName = "")),
        )

        assertEquals(SessionRecovery.SignInToRestore(displayName = null), vm.stateFlow.value.recovery)
    }

    @Test
    fun anonymousRecord_outweighsTheRouteArg_andOffersNoSignIn() = runUnitTest {
        val vm = buildViewModel(
            wasAnonymous = false,
            stranded = FakeStrandedAccountStore(
                StrandedAccount(userId = "u1", displayName = "QuietEight49", isAnonymous = true),
            ),
        )

        assertEquals(SessionRecovery.GuestOnly, vm.stateFlow.value.recovery)
    }

    @Test
    fun anonymousSession_withNoRecord_offersNoSignIn() = runUnitTest {
        val vm = buildViewModel(wasAnonymous = true)

        assertEquals(SessionRecovery.GuestOnly, vm.stateFlow.value.recovery)
    }

    @Test
    fun offline_offersNeitherSignInNorStartingOver() = runUnitTest {
        val vm = buildViewModel(
            appState = FakeAppState(offline = true),
            stranded = FakeStrandedAccountStore(
                StrandedAccount(userId = "u1", displayName = "LuckyJack66"),
            ),
        )

        assertEquals(SessionRecovery.Offline, vm.stateFlow.value.recovery)
    }

    @Test
    fun whenConnectionReturns_theOfflineScreenBecomesTheRecoveryScreen() = runUnitTest {
        val appState = FakeAppState(offline = true)
        val vm = buildViewModel(
            appState = appState,
            stranded = FakeStrandedAccountStore(
                StrandedAccount(userId = "u1", displayName = "LuckyJack66"),
            ),
        )
        assertEquals(SessionRecovery.Offline, vm.stateFlow.value.recovery)

        appState.offline.value = false

        assertEquals(SessionRecovery.SignInToRestore("LuckyJack66"), vm.stateFlow.value.recovery)
    }

    @Test
    fun abandon_clearsTheRecord_soTheDeviceCanMintAgain() = runUnitTest {
        val stranded = FakeStrandedAccountStore(
            StrandedAccount(userId = "u1", displayName = "LuckyJack66"),
        )
        val vm = buildViewModel(stranded = stranded)

        vm.takeAction(SessionExpiredViewModel.Action.Abandon)

        assertNull(stranded.read())
    }

    @Test
    fun abandon_signsOut_clearsOnboarded_andEmitsAbandoned() = runUnitTest {
        val auth = FakeAuthRepository(retryOutcome = AuthState.Unauthenticated())
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        val vm = buildViewModel(auth = auth, cache = cache)

        vm.eventFlow.test {
            vm.takeAction(SessionExpiredViewModel.Action.Abandon)
            assertEquals(SessionExpiredViewModel.Event.Abandoned, awaitItem())
        }
        assertTrue(auth.signedOut)
        assertEquals(false, cache.get().hasUserOnboarded)
    }

    @Test
    fun retry_whenSessionRecovers_emitsRestored_andMarksOnboarded() = runUnitTest {
        val auth = FakeAuthRepository(
            retryOutcome = AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"),
        )
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val vm = buildViewModel(auth = auth, cache = cache)

        vm.eventFlow.test {
            vm.takeAction(SessionExpiredViewModel.Action.Retry)
            assertEquals(SessionExpiredViewModel.Event.SessionRestored, awaitItem())
        }
        assertTrue(cache.get().hasUserOnboarded)
    }

    @Test
    fun retry_whenStillUnauthenticated_marksRetryFailed_noEvent() = runUnitTest {
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val vm = buildViewModel(cache = cache)

        vm.takeAction(SessionExpiredViewModel.Action.Retry)

        assertEquals(false, vm.stateFlow.value.retrying)
        assertTrue(vm.stateFlow.value.retryFailed)
        assertEquals(false, cache.get().hasUserOnboarded)
    }

    @Test
    fun retry_leavesTheRecordAlone_theAuthTransitionOwnsClearingIt() = runUnitTest {
        val stranded = FakeStrandedAccountStore(
            StrandedAccount(userId = "u1", displayName = "LuckyJack66"),
        )
        val vm = buildViewModel(
            auth = FakeAuthRepository(
                retryOutcome = AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"),
            ),
            stranded = stranded,
        )

        vm.takeAction(SessionExpiredViewModel.Action.Retry)

        assertEquals("LuckyJack66", stranded.read()?.displayName)
    }

    private fun buildViewModel(
        auth: FakeAuthRepository = FakeAuthRepository(retryOutcome = AuthState.Unauthenticated()),
        cache: AppCache = FakeAppCache(AppData()),
        stranded: StrandedAccountStore = FakeStrandedAccountStore(),
        appState: AppState = FakeAppState(),
        wasAnonymous: Boolean = false,
    ) = SessionExpiredViewModel(
        authRepository = auth,
        appCache = cache,
        strandedAccounts = stranded,
        appState = appState,
        wasAnonymous = wasAnonymous,
    )

    private class FakeAppState(offline: Boolean = false) : AppState {
        val offline = MutableStateFlow(offline)
        override val isOffline: StateFlow<Boolean> get() = offline
        override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private class FakeStrandedAccountStore(
        private var account: StrandedAccount? = null,
    ) : StrandedAccountStore {
        override suspend fun read(): StrandedAccount? = account
        override suspend fun write(account: StrandedAccount) { this.account = account }
        override suspend fun clear() { account = null }
    }

    private class FakeAppCache(initial: AppData) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class FakeAuthRepository(
        private val retryOutcome: AuthState,
    ) : AuthRepository {
        var signedOut = false
            private set

        override fun observe(): Flow<AuthState> = error("unused")
        override suspend fun current(): AuthState = error("unused")
        override suspend fun retry(): AuthState = retryOutcome
        override suspend fun signOut() { signedOut = true }
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
        override suspend fun refreshSession(): RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = error("unused")
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome = error("unused")
    }
}
