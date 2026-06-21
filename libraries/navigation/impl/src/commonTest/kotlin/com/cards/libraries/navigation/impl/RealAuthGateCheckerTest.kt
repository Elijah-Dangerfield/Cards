package com.dangerfield.cards.libraries.navigation.impl

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
import com.dangerfield.cards.libraries.navigation.AuthGateRoute
import com.dangerfield.cards.libraries.navigation.AuthRequirement
import com.dangerfield.cards.libraries.navigation.GateReason
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class RealAuthGateCheckerTest : CoroutineTest() {

    private val noneRoute = Route()
    private val accountRoute = Route(authRequirement = AuthRequirement.Account)
    private val claimedRoute = Route(authRequirement = AuthRequirement.ClaimedAccount)

    @Test
    fun noneRequirement_alwaysPassesThrough() = runUnitTest {
        val checker = build()
        advanceUntilIdle()
        assertSame(noneRoute, checker.gate(noneRoute))
    }

    @Test
    fun account_whenAuthenticated_passesThrough() = runUnitTest {
        val auth = FakeAuthRepository()
        val checker = build(auth = auth)
        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        advanceUntilIdle()

        assertSame(accountRoute, checker.gate(accountRoute))
    }

    @Test
    fun account_whenUnauthenticatedAndIdle_gatesWithNeedAccount() = runUnitTest {
        val auth = FakeAuthRepository()
        val checker = build(auth = auth)
        auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        val gated = assertIs<AuthGateRoute>(checker.gate(accountRoute))
        assertEquals(GateReason.NeedAccount, gated.reason)
    }

    @Test
    fun account_whileCreationPending_gatesWithFinishingSetup() = runUnitTest {
        val auth = FakeAuthRepository()
        val creator = FakeGuestAccountCreator(
            initial = AccountCreationState.Failed(
                PendingIdentity("Foxy", "🦊", null),
                cause = RuntimeException("offline"),
            ),
        )
        val checker = build(auth = auth, creator = creator)
        auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        val gated = assertIs<AuthGateRoute>(checker.gate(accountRoute))
        assertEquals(GateReason.FinishingSetup, gated.reason, "a degraded guest should be told it's still setting up")
    }

    @Test
    fun account_beforeAuthResolves_failsClosed() = runUnitTest {
        // No auth emission yet (cached null) → an Account route is gated.
        val checker = build()
        advanceUntilIdle()

        assertIs<AuthGateRoute>(checker.gate(accountRoute))
    }

    @Test
    fun claimedAccount_whenAnonymousGuest_gatesWithNeedClaimed() = runUnitTest {
        val auth = FakeAuthRepository()
        val checker = build(auth = auth)
        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        advanceUntilIdle()

        val gated = assertIs<AuthGateRoute>(checker.gate(claimedRoute))
        assertEquals(GateReason.NeedClaimedAccount, gated.reason)
    }

    @Test
    fun claimedAccount_whenClaimed_passesThrough() = runUnitTest {
        val auth = FakeAuthRepository()
        val checker = build(auth = auth)
        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"))
        advanceUntilIdle()

        assertSame(claimedRoute, checker.gate(claimedRoute))
    }

    private fun build(
        auth: AuthRepository = FakeAuthRepository(),
        creator: GuestAccountCreator = FakeGuestAccountCreator(),
    ) = RealAuthGateChecker(
        authRepository = auth,
        guestAccountCreator = creator,
        appScope = AppCoroutineScope(dispatchers),
    )

    private class FakeGuestAccountCreator(
        initial: AccountCreationState = AccountCreationState.Idle,
    ) : GuestAccountCreator {
        override val state = MutableStateFlow(initial)
        override fun start(identity: PendingIdentity) = Unit
        override suspend fun awaitTerminal(): AccountCreationState = state.value
        override fun retry() = Unit
        override suspend fun ensureSession(fallbackIdentity: PendingIdentity): AccountCreationState = state.value
    }

    private class FakeAuthRepository : AuthRepository {
        private val flow = MutableSharedFlow<AuthState>(replay = 1, extraBufferCapacity = 4)
        fun emit(state: AuthState) { flow.tryEmit(state) }

        override fun observe(): Flow<AuthState> = flow
        override suspend fun current(): AuthState = error("unused")
        override suspend fun retry(): AuthState = error("unused")
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
