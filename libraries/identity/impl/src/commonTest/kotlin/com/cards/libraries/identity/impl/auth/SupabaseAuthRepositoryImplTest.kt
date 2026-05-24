package com.dangerfield.cards.libraries.identity.impl.auth

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.impl.MeDto
import com.dangerfield.cards.libraries.identity.impl.PatchMeRequest
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import com.dangerfield.cards.libraries.identity.impl.AvatarPackResponseDto
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [SupabaseAuthRepositoryImpl]'s resolve loop + token contract.
 *
 * The interesting edges all flow through the [SupabaseAuthGateway] seam:
 *  - cold-boot resolve: `Initializing → … → Authenticated`,
 *  - cold-boot anonymous bootstrap: `NotAuthenticated → signInAnonymously →
 *    Authenticated`,
 *  - exhausted resolve attempts (gateway stays Transient forever),
 *  - sign-in failure during anon bootstrap → `Unauthenticated(cause)`,
 *  - `accessToken()` returns the gateway token when Authenticated,
 *    `null` when Unauthenticated,
 *  - `retry()` is a no-op when already Authenticated,
 *  - `signOut()` flips state and dispatches `AppEvent.SignedOut`,
 *  - `current()` suspends pre-resolve, then unsuspends after.
 *
 * Tests deliberately *don't* construct a real `SupabaseClient` — the
 * gateway interface is the unit-test seam. Tests for the sign-in /
 * sign-up / link error-mapping outcomes belong in a separate file (they
 * exercise the supabase-kt exception classes, which are not synthesizable
 * cheaply from commonTest yet).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseAuthRepositoryImplTest : CoroutineTest() {

    // ---------- resolve loop ----------

    @Test
    fun resolve_authenticatedOnFirstPoll_emitsAuthenticatedImmediately() = runUnitTest {
        // Warm boot path: supabase-kt already has a persisted session.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals("user-1", state.userId)
        assertEquals(false, state.isAnonymous)
        assertEquals("a@b.com", state.email)
        assertEquals(0, gateway.signInAnonymouslyCalls, "no anon sign-in when already authenticated")
    }

    @Test
    fun resolve_notAuthenticated_triggersAnonSignIn_andSettles() = runUnitTest {
        // Cold-boot fresh-install path: no session → anon sign-in → resolve.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onSignInAnonymously = { advanceToAuthenticated(anonymousSession()) },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals(true, state.isAnonymous)
        assertNull(state.email, "anonymous users surface a null email regardless of supabase placeholder")
        assertEquals(1, gateway.signInAnonymouslyCalls)
    }

    @Test
    fun resolve_initializing_loopsUntilSettled() = runUnitTest {
        // Mid-init path: status reports Initializing once, then Authenticated.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Initializing,
            session = claimedSession(),
            statusSequence = listOf(
                AuthGatewayStatus.Initializing,
                AuthGatewayStatus.Authenticated,
            ),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        assertIs<AuthState.Authenticated>(repo.current())
        assertTrue(gateway.statusReads >= 2, "resolve must re-poll after a Transient status")
    }

    @Test
    fun resolve_signInAnonymouslyThrows_emitsUnauthenticatedWithCause() = runUnitTest {
        // Offline + fresh install: anon sign-in throws (network). Resolve
        // bails to Unauthenticated with the cause so callers see why.
        val boom = RuntimeException("offline")
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onSignInAnonymously = { throw boom },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(boom, state.cause)
    }

    @Test
    fun resolve_alwaysTransient_exhaustsAttempts_andEmitsUnauthenticatedNoCause() = runUnitTest {
        // Pathological path: gateway never settles. After MaxResolveAttempts
        // (5) iterations we emit Unauthenticated(cause=null) — distinguishable
        // from a real error because cause is null.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Initializing,
            session = null,
            // Every poll reports Initializing forever.
            statusSequence = List(10) { AuthGatewayStatus.Initializing },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Unauthenticated>(repo.current())
        assertNull(state.cause, "exhausted resolve has no exception attached")
    }

    @Test
    fun observe_emitsEachStateTransition() = runUnitTest {
        // After the initial resolve, every subsequent emit lands on the
        // shared flow. We pin the no-cause Unauthenticated → signOut path.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        repo.observe().test {
            assertIs<AuthState.Authenticated>(awaitItem())
            repo.signOut()
            assertIs<AuthState.Unauthenticated>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- current() suspension ----------

    @Test
    fun current_suspendsUntilResolveCompletes() = runUnitTest {
        // The whole reason for the resolve loop: callers (including the
        // network client's bearer plugin) suspend until auth lands.
        val sessionGate = CompletableDeferred<Unit>()
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onSignInAnonymously = {
                sessionGate.await()
                advanceToAuthenticated(anonymousSession())
            },
        )
        val repo = build(gateway = gateway)

        val pending = async { repo.current() }
        // Yield the dispatcher to let the resolve start.
        advanceUntilIdle()
        assertTrue(pending.isActive, "current() must still be suspended until anon sign-in completes")

        sessionGate.complete(Unit)
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(pending.await())
    }

    // Token methods (accessToken / refreshAccessToken) live on
    // [GatewayAuthTokenProvider] now — see [GatewayAuthTokenProviderTest].

    // ---------- retry ----------

    @Test
    fun retry_isNoOp_whenAlreadyAuthenticated() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val readsBefore = gateway.statusReads
        val result = repo.retry()
        assertIs<AuthState.Authenticated>(result)
        assertEquals(readsBefore, gateway.statusReads, "retry must not re-poll when authed")
    }

    @Test
    fun retry_reRunsResolve_afterUnauthenticated() = runUnitTest {
        // First resolve fails (no network). retry() (e.g. on offline→online
        // flip) re-runs the loop, sees network is back, lands authed.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onSignInAnonymously = { throw RuntimeException("offline") },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()
        assertIs<AuthState.Unauthenticated>(repo.current())

        gateway.onSignInAnonymously = { advanceToAuthenticated(anonymousSession()) }
        val result = repo.retry()
        assertIs<AuthState.Authenticated>(result)
    }

    // ---------- signOut ----------

    @Test
    fun signOut_clearsState_andDispatchesSignedOutEvent() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val events = RecordingEventBus()
        val repo = build(gateway = gateway, appEventBus = events)
        advanceUntilIdle()

        repo.signOut()
        assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(1, gateway.signOutCalls)
        assertEquals(listOf<AppEvent>(AppEvent.SignedOut), events.dispatched)
    }

    @Test
    fun signOut_proceedsEvenIfGatewayThrows() = runUnitTest {
        // Server-side sign-out can fail (offline, expired token, etc.).
        // The local state still flips to Unauthenticated — leaving the
        // user "stuck signed in" client-side would be worse.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
            onSignOut = { throw RuntimeException("network") },
        )
        val events = RecordingEventBus()
        val repo = build(gateway = gateway, appEventBus = events)
        advanceUntilIdle()

        repo.signOut()
        assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(listOf<AppEvent>(AppEvent.SignedOut), events.dispatched)
    }

    // ---------- scaffolding ----------

    private fun build(
        gateway: FakeSupabaseAuthGateway,
        appEventBus: AppEventBus = NoOpEventBus,
    ): SupabaseAuthRepositoryImpl = SupabaseAuthRepositoryImpl(
        gateway = gateway,
        authBootstrap = AuthBootstrap(gateway),
        profileApi = UnusedProfileApi,
        appEventBus = appEventBus,
        appScope = AppCoroutineScope(dispatchers),
    )

    private fun anonymousSession(
        userId: String = "anon-user",
        accessToken: String = "tok-anon",
    ): GatewaySession = GatewaySession(
        userId = userId,
        email = null,
        accessToken = accessToken,
        isAnonymous = true,
        isEmailConfirmed = true,
    )

    private fun claimedSession(
        userId: String = "user-1",
        email: String = "a@b.com",
        accessToken: String = "tok-claimed",
    ): GatewaySession = GatewaySession(
        userId = userId,
        email = email,
        accessToken = accessToken,
        isAnonymous = false,
        isEmailConfirmed = true,
    )

    private object NoOpEventBus : AppEventBus {
        override fun dispatch(event: AppEvent) = Unit
    }

    private class RecordingEventBus : AppEventBus {
        val dispatched: MutableList<AppEvent> = mutableListOf()
        override fun dispatch(event: AppEvent) {
            dispatched += event
        }
    }

    private object UnusedProfileApi : ProfileApi {
        override suspend fun me(): MeDto = error("ProfileApi not used by these tests")
        override suspend fun patchMe(request: PatchMeRequest): MeDto =
            error("ProfileApi not used by these tests")
        override suspend fun avatars(): AvatarPackResponseDto =
            error("ProfileApi not used by these tests")
        override suspend fun deleteMe(): HttpResponse =
            error("ProfileApi not used by these tests")
    }
}

/**
 * In-memory [SupabaseAuthGateway] for unit tests. Tracks call counts +
 * lets the test drive every method outcome via small callbacks. Default
 * mutations (`onSignInAnonymously`, `onSignOut`, `onRefreshSession`)
 * succeed silently and leave session state untouched — overrides set
 * `currentStatus` / `currentSession` as needed.
 *
 * [statusSequence] lets a test simulate a status that flips across
 * resolve passes (e.g. Initializing → Authenticated). If null, every
 * read returns [initialStatus] (which mutations may overwrite).
 */
internal class FakeSupabaseAuthGateway(
    initialStatus: AuthGatewayStatus,
    session: GatewaySession?,
    statusSequence: List<AuthGatewayStatus>? = null,
    var onSignInAnonymously: suspend FakeSupabaseAuthGateway.() -> Unit = {},
    var onSignOut: suspend FakeSupabaseAuthGateway.() -> Unit = {},
    var onRefreshSession: suspend FakeSupabaseAuthGateway.() -> Unit = {},
) : SupabaseAuthGateway {

    private var status: AuthGatewayStatus = initialStatus
    private var session: GatewaySession? = session
    private val statusIterator: Iterator<AuthGatewayStatus>? = statusSequence?.iterator()

    var statusReads: Int = 0
        private set
    var signInAnonymouslyCalls: Int = 0
        private set
    var refreshSessionCalls: Int = 0
        private set
    var signOutCalls: Int = 0
        private set

    /**
     * Flip the gateway to Authenticated with the given session. Use from
     * inside an `onSignInAnonymously` block to model "supabase-kt's status
     * moves to Authenticated after the anon sign-in resolves".
     */
    fun advanceToAuthenticated(session: GatewaySession) {
        status = AuthGatewayStatus.Authenticated
        this.session = session
    }

    fun replaceSession(session: GatewaySession?) {
        this.session = session
    }

    /**
     * Force [currentStatus] to start returning [next] on subsequent reads.
     * Used to model a gateway whose status flipped out of band (e.g. signOut
     * cleared the session — next status read should be NotAuthenticated).
     */
    fun setStatus(next: AuthGatewayStatus) {
        status = next
    }

    override suspend fun awaitInitialization() { /* no-op for tests */ }

    override fun currentStatus(): AuthGatewayStatus {
        statusReads++
        // If the test supplied a per-poll sequence, drain that first.
        return statusIterator?.takeIf { it.hasNext() }?.next() ?: status
    }

    override fun currentSession(): GatewaySession? = session

    override suspend fun signInAnonymously() {
        signInAnonymouslyCalls++
        onSignInAnonymously()
    }

    override suspend fun refreshSession() {
        refreshSessionCalls++
        onRefreshSession()
    }

    override suspend fun signInWithEmail(email: String, password: String): Unit =
        error("signInWithEmail not stubbed for these tests")

    override suspend fun signUpWithEmail(email: String, password: String): Unit =
        error("signUpWithEmail not stubbed for these tests")

    override suspend fun resendVerificationEmail(email: String): Unit =
        error("resendVerificationEmail not stubbed for these tests")

    override suspend fun signOut() {
        signOutCalls++
        onSignOut()
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider): Unit =
        error("linkOAuthIdentity not stubbed for these tests")

    override suspend fun signInWithOAuth(provider: OAuthProvider): Unit =
        error("signInWithOAuth not stubbed for these tests")

    override suspend fun linkEmailIdentity(email: String, password: String): Unit =
        error("linkEmailIdentity not stubbed for these tests")
}
