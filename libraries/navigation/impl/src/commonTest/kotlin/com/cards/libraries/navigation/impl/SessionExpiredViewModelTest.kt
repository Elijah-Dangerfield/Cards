package com.dangerfield.cards.libraries.navigation.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionExpiredViewModelTest : CoroutineTest() {

    @Test
    fun retry_whenSessionRecovers_emitsRestored_andMarksOnboarded() = runUnitTest {
        val auth = FakeAuthRepository(
            retryOutcome = AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"),
        )
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val vm = SessionExpiredViewModel(auth, cache)

        vm.eventFlow.test {
            vm.takeAction(SessionExpiredViewModel.Action.Retry)
            assertEquals(SessionExpiredViewModel.Event.SessionRestored, awaitItem())
        }
        assertTrue(cache.get().hasUserOnboarded)
    }

    @Test
    fun retry_whenStillUnauthenticated_marksRetryFailed_noEvent() = runUnitTest {
        val auth = FakeAuthRepository(retryOutcome = AuthState.Unauthenticated())
        val cache = FakeAppCache(AppData(hasUserOnboarded = false))
        val vm = SessionExpiredViewModel(auth, cache)

        vm.takeAction(SessionExpiredViewModel.Action.Retry)

        assertEquals(false, vm.stateFlow.value.retrying)
        assertTrue(vm.stateFlow.value.retryFailed)
        assertEquals(false, cache.get().hasUserOnboarded)
    }

    @Test
    fun logout_signsOut_clearsOnboarded_andEmitsLoggedOut() = runUnitTest {
        val auth = FakeAuthRepository(retryOutcome = AuthState.Unauthenticated())
        val cache = FakeAppCache(AppData(hasUserOnboarded = true))
        val vm = SessionExpiredViewModel(auth, cache)

        vm.eventFlow.test {
            vm.takeAction(SessionExpiredViewModel.Action.Logout)
            assertEquals(SessionExpiredViewModel.Event.LoggedOut, awaitItem())
        }
        assertTrue(auth.signedOut)
        assertEquals(false, cache.get().hasUserOnboarded)
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
