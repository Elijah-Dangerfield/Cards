package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
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
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.CacheJsonSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins [DefaultGuestAccountCreator]: success = a session exists, profile apply
 * is best-effort, offline = Failed(identity) + retryable.
 */
class DefaultGuestAccountCreatorTest : CoroutineTest() {

    private val identity = PendingIdentity(
        displayName = "Foxy",
        avatarEmoji = "🦊",
        avatarBackgroundColor = "#ff6b35",
    )

    @Test
    fun start_success_appliesProfile_andSucceeds() = runUnitTest {
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.Success)
        val profile = FakeProfileRepository()
        val creator = build(auth, profile)

        creator.start(identity)
        advanceUntilIdle()

        assertIs<AccountCreationState.Succeeded>(creator.state.value)
        assertEquals(1, auth.createGuestCalls)
        assertEquals(identity.displayName, profile.lastUpdate?.displayName)
        assertEquals(identity.avatarEmoji, profile.lastUpdate?.avatarEmoji)
    }

    @Test
    fun start_profileUpdateFailure_isNonFatal_stillSucceeds() = runUnitTest {
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.Success)
        val profile = FakeProfileRepository(updateThrows = RuntimeException("patch failed"))
        val creator = build(auth, profile)

        creator.start(identity)
        advanceUntilIdle()

        // The account exists even though the name patch failed.
        assertIs<AccountCreationState.Succeeded>(creator.state.value)
    }

    @Test
    fun start_sessionFailure_isFailed_withHeldIdentity() = runUnitTest {
        val boom = RuntimeException("offline")
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.NetworkError(boom))
        val creator = build(auth, FakeProfileRepository())

        creator.start(identity)
        advanceUntilIdle()

        val failed = assertIs<AccountCreationState.Failed>(creator.state.value)
        assertEquals(identity, failed.identity)
        assertEquals(boom, failed.cause)
    }

    @Test
    fun retry_afterFailure_reRuns_andCanSucceed() = runUnitTest {
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.NetworkError(RuntimeException("offline")))
        val creator = build(auth, FakeProfileRepository())

        creator.start(identity)
        advanceUntilIdle()
        assertIs<AccountCreationState.Failed>(creator.state.value)

        // Network is back.
        auth.guestOutcome = SignInOutcome.Success
        creator.retry()
        advanceUntilIdle()

        assertIs<AccountCreationState.Succeeded>(creator.state.value)
        assertEquals(2, auth.createGuestCalls)
    }

    @Test
    fun start_isIgnored_onceSucceeded() = runUnitTest {
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.Success)
        val creator = build(auth, FakeProfileRepository())

        creator.start(identity)
        advanceUntilIdle()
        creator.start(identity) // should be a no-op
        advanceUntilIdle()

        assertEquals(1, auth.createGuestCalls, "a second start after success must not re-create")
    }

    @Test
    fun awaitTerminal_returnsTheTerminalState() = runUnitTest {
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.Success)
        val creator = build(auth, FakeProfileRepository())

        creator.start(identity)
        val terminal = creator.awaitTerminal()

        assertIs<AccountCreationState.Succeeded>(terminal)
    }

    @Test
    fun start_success_clearsPendingStore() = runUnitTest {
        val store = PendingGuestAccountStore(InMemoryCacheFactory)
        val creator = build(FakeAuthRepository(SignInOutcome.Success), FakeProfileRepository(), store)

        creator.start(identity)
        advanceUntilIdle()

        assertNull(store.read(), "a created account is no longer owed")
    }

    @Test
    fun start_failure_keepsPendingInStore_forLaterResume() = runUnitTest {
        val store = PendingGuestAccountStore(InMemoryCacheFactory)
        val auth = FakeAuthRepository(SignInOutcome.NetworkError(RuntimeException("offline")))
        val creator = build(auth, FakeProfileRepository(), store)

        creator.start(identity)
        advanceUntilIdle()

        assertIs<AccountCreationState.Failed>(creator.state.value)
        assertEquals(identity, store.read(), "a failed creation stays owed for the next launch")
    }

    @Test
    fun init_resumesOwedCreation_fromStore() = runUnitTest {
        // Simulate a prior session that onboarded offline: the store still owes
        // a guest account. On construction (app launch) the creator resumes it.
        val store = PendingGuestAccountStore(InMemoryCacheFactory)
        store.set(identity)
        val auth = FakeAuthRepository(SignInOutcome.Success)

        val creator = build(auth, FakeProfileRepository(), store)
        advanceUntilIdle()

        assertIs<AccountCreationState.Succeeded>(creator.state.value)
        assertEquals(1, auth.createGuestCalls)
        assertNull(store.read())
    }

    @Test
    fun init_owedButAlreadyAuthenticated_reconcilesWithoutRecreating() = runUnitTest {
        // Last session created the account but the clear() didn't persist (crash
        // in between). Resume must reconcile, not mint a second anon account.
        val store = PendingGuestAccountStore(InMemoryCacheFactory)
        store.set(identity)
        val auth = FakeAuthRepository(
            guestOutcome = SignInOutcome.Success,
            currentState = AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null),
        )

        val creator = build(auth, FakeProfileRepository(), store)
        advanceUntilIdle()

        assertIs<AccountCreationState.Succeeded>(creator.state.value)
        assertEquals(0, auth.createGuestCalls, "must not mint a second account")
        assertNull(store.read())
    }

    @Test
    fun init_nothingOwed_staysIdle() = runUnitTest {
        val auth = FakeAuthRepository(SignInOutcome.Success)
        val creator = build(auth, FakeProfileRepository())
        advanceUntilIdle()

        assertIs<AccountCreationState.Idle>(creator.state.value)
        assertEquals(0, auth.createGuestCalls)
    }

    @Test
    fun goingOnline_retriesFailedCreation_midSession() = runUnitTest {
        val appState = FakeAppState(isOffline = MutableStateFlow(true)) // start offline
        val auth = FakeAuthRepository(SignInOutcome.NetworkError(RuntimeException("offline")))
        val creator = build(auth, FakeProfileRepository(), appState = appState)

        creator.start(identity)
        advanceUntilIdle()
        assertIs<AccountCreationState.Failed>(creator.state.value)
        assertEquals(1, auth.createGuestCalls)

        // Connectivity returns and creation can now succeed.
        auth.guestOutcome = SignInOutcome.Success
        appState.isOffline.value = false
        advanceUntilIdle()

        assertIs<AccountCreationState.Succeeded>(creator.state.value)
        assertEquals(2, auth.createGuestCalls, "online flip must re-attempt the failed creation")
    }

    @Test
    fun retry_isNoOp_whenNotFailed() = runUnitTest {
        val auth = FakeAuthRepository(guestOutcome = SignInOutcome.Success)
        val creator = build(auth, FakeProfileRepository())

        creator.retry() // Idle → no-op
        advanceUntilIdle()

        assertIs<AccountCreationState.Idle>(creator.state.value)
        assertEquals(0, auth.createGuestCalls)
    }

    private fun build(
        auth: AuthRepository,
        profile: ProfileRepository,
        store: PendingGuestAccountStore = PendingGuestAccountStore(InMemoryCacheFactory),
        appState: FakeAppState = FakeAppState(),
    ) = DefaultGuestAccountCreator(
        authRepository = auth,
        profileRepository = profile,
        pendingStore = store,
        appState = appState,
        appScope = AppCoroutineScope(dispatchers),
    )

    private class FakeAppState(
        override val isOffline: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) : AppState {
        override val isBlockActive: MutableStateFlow<Boolean> = MutableStateFlow(false)
    }

    private object InMemoryCacheFactory : CacheFactory {
        override fun <T : Any> inMemory(defaultValue: () -> T): Cache<T> = FakeCache(defaultValue)
        override fun <T : Any> persistent(
            name: String,
            serializer: CacheJsonSerializer<T>,
            loadEagerly: Boolean,
        ): Cache<T> = FakeCache { runBlocking { serializer.read(null) } }
    }

    private class FakeCache<T : Any>(private val initial: () -> T) : Cache<T> {
        private val state = MutableStateFlow(initial())
        override val updates: Flow<T> = state
        override suspend fun get(): T = state.value
        override suspend fun set(value: T) { state.value = value }
        override suspend fun clear() { state.value = initial() }
    }

    private class FakeAuthRepository(
        var guestOutcome: SignInOutcome,
        private val currentState: AuthState = AuthState.Unauthenticated(),
    ) : AuthRepository {
        var createGuestCalls = 0
            private set

        override suspend fun createGuestSession(): SignInOutcome {
            createGuestCalls++
            return guestOutcome
        }

        override suspend fun current(): AuthState = currentState
        override fun observe(): Flow<AuthState> = emptyFlow()
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

    private class FakeProfileRepository(
        private val updateThrows: Throwable? = null,
    ) : ProfileRepository {
        data class UpdateArgs(val displayName: String?, val avatarEmoji: String?, val color: String?)
        var lastUpdate: UpdateArgs? = null
            private set

        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
            featuredBadgeIds: List<String>?,
        ): UpdateProfileOutcome {
            updateThrows?.let { throw it }
            lastUpdate = UpdateArgs(displayName, avatarEmoji, avatarBackgroundColor)
            return UpdateProfileOutcome.NotSignedIn // value is irrelevant; creator ignores it
        }

        override suspend fun current(): Profile = error("unused")
        override fun observe(): Flow<Profile> = emptyFlow()
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    }
}
