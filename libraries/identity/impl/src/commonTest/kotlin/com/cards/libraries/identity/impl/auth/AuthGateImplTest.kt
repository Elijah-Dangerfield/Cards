package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the [AuthGateImpl] verdict table — the sole producer of auth-readiness.
 *
 * The pre-refactor nav-gate givens from RealAuthGateChecker are ported 1:1,
 * with two intentional deltas over the old `reasonFor`:
 *  - a rejected session now reads [AuthReason.SessionExpired] (was NeedAccount),
 *  - the stranded-but-healable state (onboarded + online + session-less) now
 *    reads [AuthReason.FinishingSetup] and kicks the healer (was NeedAccount).
 *
 * Healer *kick* behavior is asserted here via the real [GuestSessionHealer]
 * (mint = the fake creator's ensureSession count); the healer's own eligibility
 * matrix and single-flight live in GuestSessionHealerTest.
 */
class AuthGateImplTest : CoroutineTest() {

    @Test
    fun none_isReady_evenBeforeAuthResolves() = runUnitTest {
        val h = build()
        assertSame(AuthVerdict.Ready, h.gate.verdict(AuthRequirement.None))
    }

    @Test
    fun account_whenAuthenticated_isReady() = runUnitTest {
        val h = build()
        h.auth.emit(authed())
        advanceUntilIdle()

        assertSame(AuthVerdict.Ready, h.gate.verdict(AuthRequirement.Account))
    }

    @Test
    fun account_whenAuthenticatedButOffline_isStillReady() = runUnitTest {
        // We hold a (possibly stale) session; the call must proceed and fail at
        // transport as an ordinary network error, never through the gate.
        val h = build(offline = true)
        h.auth.emit(authed())
        advanceUntilIdle()

        assertSame(AuthVerdict.Ready, h.gate.verdict(AuthRequirement.Account))
    }

    @Test
    fun account_beforeAuthResolves_failsClosed_andNeverHeals() = runUnitTest {
        val h = build(onboarded = true)
        advanceUntilIdle()

        assertIs<AuthVerdict.Blocked>(h.gate.verdict(AuthRequirement.Account))
        advanceUntilIdle()
        assertEquals(0, h.auth.retryCalls, "unresolved auth must not run the healer")
    }

    @Test
    fun account_whenUnauthenticatedPreOnboarding_blocksWithNeedAccount() = runUnitTest {
        val h = build(onboarded = false)
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        assertEquals(blocked(AuthReason.NeedAccount), h.gate.verdict(AuthRequirement.Account))
        advanceUntilIdle()
        assertEquals(0, h.creator.ensureCalls, "pre-onboarding there's no identity owed")
    }

    @Test
    fun account_whenUnauthenticatedAndOffline_blocksWithOffline() = runUnitTest {
        val h = build(onboarded = true, offline = true)
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        assertEquals(
            blocked(AuthReason.Offline),
            h.gate.verdict(AuthRequirement.Account),
            "offline + unconfirmed session should read as offline, not 'account needed'",
        )
        advanceUntilIdle()
        assertEquals(0, h.creator.ensureCalls, "can't heal offline")
    }

    @Test
    fun account_whileCreationDegraded_blocksWithFinishingSetup() = runUnitTest {
        val h = build(creation = failedCreation())
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        assertEquals(blocked(AuthReason.FinishingSetup), h.gate.verdict(AuthRequirement.Account))
    }

    @Test
    fun account_whenCreationDegradedAndOffline_prefersFinishingSetupOverOffline() = runUnitTest {
        val h = build(creation = failedCreation(), offline = true)
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        assertEquals(
            blocked(AuthReason.FinishingSetup),
            h.gate.verdict(AuthRequirement.Account),
            "an actively-healing guest should still be told setup is finishing",
        )
    }

    @Test
    fun account_whenStranded_blocksWithFinishingSetup_andKicksHealerOnce() = runUnitTest {
        // Intentional delta: the old nav gate said NeedAccount here.
        val h = build(onboarded = true)
        h.auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        assertEquals(blocked(AuthReason.FinishingSetup), h.gate.verdict(AuthRequirement.Account))
        advanceUntilIdle()
        assertEquals(1, h.creator.ensureCalls, "a healable verdict kicks the healer")
    }

    @Test
    fun account_whenSessionExpired_blocksWithSessionExpired_andNeverHeals() = runUnitTest {
        // Intentional delta: the old nav gate said NeedAccount here.
        val h = build(onboarded = true)
        h.auth.emit(AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.SessionExpired))
        advanceUntilIdle()

        assertEquals(blocked(AuthReason.SessionExpired), h.gate.verdict(AuthRequirement.Account))
        advanceUntilIdle()
        assertEquals(0, h.auth.retryCalls, "a server-killed session routes to re-auth, not heal")
    }

    @Test
    fun account_whenSignedOut_blocksWithNeedAccount_andNeverHeals() = runUnitTest {
        val h = build(onboarded = true)
        h.auth.emit(AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.SignedOut))
        advanceUntilIdle()

        assertEquals(blocked(AuthReason.NeedAccount), h.gate.verdict(AuthRequirement.Account))
        advanceUntilIdle()
        assertEquals(0, h.auth.retryCalls, "a deliberate sign-out must not be resurrected")
    }

    @Test
    fun claimedAccount_whenAnonymousGuest_blocksWithNeedClaimed() = runUnitTest {
        val h = build()
        h.auth.emit(authed(isAnonymous = true))
        advanceUntilIdle()

        assertEquals(blocked(AuthReason.NeedClaimedAccount), h.gate.verdict(AuthRequirement.ClaimedAccount))
    }

    @Test
    fun claimedAccount_whenClaimed_isReady() = runUnitTest {
        val h = build()
        h.auth.emit(authed(isAnonymous = false, email = "a@b.com"))
        advanceUntilIdle()

        assertSame(AuthVerdict.Ready, h.gate.verdict(AuthRequirement.ClaimedAccount))
    }

    @Test
    fun awaitVerdict_waitsForAuthToResolve() = runUnitTest {
        val h = build()
        val pending = async { h.gate.awaitVerdict(AuthRequirement.Account) }
        advanceUntilIdle()
        assertFalse(pending.isCompleted, "must not answer before auth resolves")

        h.auth.emit(authed())
        advanceUntilIdle()
        assertSame(AuthVerdict.Ready, pending.await())
    }

    @Test
    fun awaitVerdict_none_answersBeforeAuthResolves() = runUnitTest {
        val h = build()
        assertSame(AuthVerdict.Ready, h.gate.awaitVerdict(AuthRequirement.None))
    }

    private class Harness(
        val auth: FakeAuthRepository,
        val creator: FakeGuestAccountCreator,
        val gate: AuthGateImpl,
    )

    private fun build(
        onboarded: Boolean = false,
        offline: Boolean = false,
        creation: AccountCreationState = AccountCreationState.Idle,
    ): Harness {
        val auth = FakeAuthRepository()
        val creator = FakeGuestAccountCreator(creation)
        val appCache = FakeAppCache(onboarded)
        val appState = FakeAppState(offline)
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
        return Harness(auth, creator, gate)
    }

    private fun authed(isAnonymous: Boolean = true, email: String? = null) =
        AuthState.Authenticated(userId = "u1", isAnonymous = isAnonymous, email = email)

    private fun blocked(reason: AuthReason) = AuthVerdict.Blocked(reason)

    private fun failedCreation() = AccountCreationState.Failed(
        PendingIdentity("Foxy", "🦊", null),
        cause = RuntimeException("offline"),
    )

    private class FakeAppState(offline: Boolean) : AppState {
        override val isOffline: StateFlow<Boolean> = MutableStateFlow(offline)
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

    private class FakeGuestAccountCreator(
        initial: AccountCreationState,
    ) : GuestAccountCreator {
        var ensureCalls = 0
            private set

        override val state = MutableStateFlow(initial)
        override fun start(identity: PendingIdentity) = error("unused")
        override fun retry() = error("unused")
        override suspend fun awaitTerminal(): AccountCreationState = state.value
        override suspend fun ensureSession(fallbackIdentity: PendingIdentity): AccountCreationState {
            ensureCalls++
            return AccountCreationState.Succeeded
        }
    }

    private class FakeAuthRepository : AuthRepository {
        private val flow = MutableSharedFlow<AuthState>(replay = 1, extraBufferCapacity = 4)
        private var latest: AuthState = AuthState.Unauthenticated()
        var retryCalls = 0
            private set

        fun emit(state: AuthState) {
            latest = state
            flow.tryEmit(state)
        }

        override fun observe(): Flow<AuthState> = flow
        override suspend fun current(): AuthState = latest
        override suspend fun retry(): AuthState {
            retryCalls++
            return latest
        }
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
