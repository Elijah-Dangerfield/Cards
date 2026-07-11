package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.AppEvents
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the InAppMessageManager's display gate:
 *  - At most one dialog per trigger pass (auth activation / warm fg / reconnect)
 *  - A pass while one's still up doesn't consume another
 *  - Sync runs before consume (so freshly-arrived messages show up)
 *  - Sync failure doesn't block consume (offline-first)
 *  - No pass fires without a session; auth arriving fires the pass boot skipped
 *  - A user change clears current immediately
 */
class InAppMessageManagerImplTest : CoroutineTest() {

    @Test
    fun authActive_consumesNextDialog_andSurfacesIt() = runUnitTest {
        val repo = FakeRepo().apply { enqueue(message("a")) }
        val f = fixture(repo)

        f.manager.current.test {
            assertNull(awaitItem())
            f.signIn("u1")
            val surfaced = awaitItem()
            assertNotNull(surfaced)
            assertEquals("a", surfaced.id)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, repo.syncCalls, "sync runs before consume")
        assertEquals(1, repo.consumeCalls)
    }

    @Test
    fun warmForegroundWhileDialogStillUp_doesNotConsumeAnotherDialog() = runUnitTest {
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        assertEquals("a", f.manager.current.value?.id)

        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals("a", f.manager.current.value?.id, "still 'a', not 'b'")
        assertEquals(1, repo.consumeCalls, "no second consume while dialog up")
    }

    @Test
    fun afterDismiss_nextForegroundConsumesNext() = runUnitTest {
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        assertEquals("a", f.manager.current.value?.id)
        f.manager.dismissCurrent()
        assertNull(f.manager.current.value)

        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals("b", f.manager.current.value?.id)
    }

    @Test
    fun coldBootForeground_doesNotDoubleConsume() = runUnitTest {
        // ColdBoot + OnForeground(isColdBoot=true) fire alongside the auth
        // resolve. The activation pass owns that moment; the cold foreground
        // must not run a second pass and consume another dialog.
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val f = fixture(repo)
        f.signIn("u1")
        f.bus.dispatch(AppEvent.ColdBoot)
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))
        runCurrent()
        assertEquals("a", f.manager.current.value?.id)
        assertEquals(1, repo.consumeCalls)
    }

    @Test
    fun syncFailure_stillConsumes_fromLocalCache() = runUnitTest {
        // Offline-first: a failed sync shouldn't block surfacing a
        // cached message.
        val repo = FakeRepo().apply {
            enqueue(message("cached"))
            syncReturns = Result.failure(RuntimeException("net"))
        }
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        assertEquals("cached", f.manager.current.value?.id)
    }

    @Test
    fun noEligibleDialog_leavesCurrentNull() = runUnitTest {
        val repo = FakeRepo() // empty
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        assertNull(f.manager.current.value)
    }

    @Test
    fun userChanged_clearsCurrent_immediately() = runUnitTest {
        val repo = FakeRepo().apply { enqueue(message("a")) }
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        assertNotNull(f.manager.current.value)
        f.manager.onUserChanged(AppEvent.UserChanged(previous = "u1", current = null))
        assertNull(f.manager.current.value)
    }

    @Test
    fun dismissCurrent_doesNotAutomaticallySurfaceNext() = runUnitTest {
        // Dismiss without a new trigger = no new dialog. The next
        // foreground is when we re-evaluate.
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        f.manager.dismissCurrent()
        runCurrent()
        assertNull(f.manager.current.value, "no auto-pop without a foreground event")
    }

    @Test
    fun unauthenticatedBoot_runsNoPass_untilAuthArrives() = runUnitTest {
        // The 401-on-init fix, level-shaped: a session-less boot must not fire
        // a tokenless sync. The pass runs when auth arrives instead.
        val repo = FakeRepo().apply { enqueue(message("cached")) }
        val f = fixture(repo)
        f.auth.emit(AuthState.Unauthenticated())
        f.bus.dispatch(AppEvent.ColdBoot)
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))
        runCurrent()
        assertEquals(0, repo.syncCalls, "no tokenless sync when unauthenticated")
        assertNull(f.manager.current.value)

        f.signIn("u1")
        runCurrent()
        assertEquals(1, repo.syncCalls, "auth arriving fires the pass boot skipped")
        assertEquals("cached", f.manager.current.value?.id)
    }

    @Test
    fun claim_sameIdAnonymousFlips_runsAFreshPass() = runUnitTest {
        val repo = FakeRepo()
        val f = fixture(repo)
        f.signIn("guest-1", isAnonymous = true)
        runCurrent()
        assertEquals(1, repo.syncCalls)

        f.signIn("guest-1", isAnonymous = false)
        runCurrent()
        assertEquals(2, repo.syncCalls, "a claim flushes + surfaces without any bus event")
    }

    @Test
    fun connectivityRegained_syncs() = runUnitTest {
        val repo = FakeRepo()
        val f = fixture(repo)
        f.signIn("u1")
        runCurrent()
        assertEquals(1, repo.syncCalls)

        f.appState.isOffline.value = true
        f.appState.isOffline.value = false
        advanceTimeBy(1.seconds)
        assertEquals(2, repo.syncCalls)
    }

    // ---------- scaffolding ----------

    private fun TestScope.fixture(repo: FakeRepo): Fixture {
        val bus = FakeBus()
        val auth = FakeAuthRepo()
        val appState = FakeAppState()
        val manager = InAppMessageManagerImpl(
            repository = repo,
            triggers = SyncTriggers(
                authRepositoryProvider = { auth },
                appEventsProvider = { AppEvents(bus) },
                appState = appState,
            ),
            registry = UserScopedWorkRegistry(),
            appScope = AppCoroutineScope(dispatchers),
        )
        return Fixture(manager, bus, auth, appState)
    }

    private class Fixture(
        val manager: InAppMessageManagerImpl,
        val bus: FakeBus,
        val auth: FakeAuthRepo,
        val appState: FakeAppState,
    ) {
        suspend fun signIn(userId: String, isAnonymous: Boolean = true) {
            auth.emit(AuthState.Authenticated(userId = userId, isAnonymous = isAnonymous, email = null))
        }
    }

    private fun message(id: String) = UserMessage(
        id = id,
        kind = UserMessageKind.Dialog,
        emoji = null,
        title = "Title $id",
        body = "Body $id",
        deepLink = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
    )

    private class FakeBus : AppEventBus {
        private val flow = MutableSharedFlow<AppEvent>(replay = 1, extraBufferCapacity = 64)
        private val liveFlow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
        override fun dispatch(event: AppEvent) {
            flow.tryEmit(event)
            liveFlow.tryEmit(event)
        }
        override fun eventStream(): Flow<AppEvent> = flow
        override fun liveEventStream(): Flow<AppEvent> = liveFlow
    }

    private class FakeAppState : AppState {
        override val isOffline = MutableStateFlow(false)
        override val isBlockActive = MutableStateFlow(false)
    }

    private class FakeRepo : UserMessageRepository {
        private val queue = ArrayDeque<UserMessage>()
        var consumeCalls: Int = 0
            private set
        var syncCalls: Int = 0
            private set
        var syncReturns: Result<Unit> = Result.success(Unit)

        fun enqueue(message: UserMessage) { queue.addLast(message) }

        override fun observeInbox(): Flow<List<UserMessage>> =
            MutableStateFlow<List<UserMessage>>(emptyList())

        override fun observeUnreadInboxCount(): Flow<Int> =
            MutableStateFlow(0)

        override suspend fun consumeNextDialog(): UserMessage? {
            consumeCalls++
            return queue.removeFirstOrNull()
        }

        override suspend fun markAllInboxShown(): Int = 0
        override suspend fun replaceCache(messages: List<UserMessage>) {}
        override suspend fun pendingAckIds(): List<String> = emptyList()
        override suspend fun sync(): Result<Unit> {
            syncCalls++
            return syncReturns
        }
    }

    private class FakeAuthRepo : AuthRepository {
        private val state = MutableSharedFlow<AuthState>(replay = 1)

        suspend fun emit(next: AuthState) = state.emit(next)

        override suspend fun current(): AuthState = state.replayCache.first()
        override fun observe(): Flow<AuthState> = state
        override suspend fun retry(): AuthState = current()
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
            error("unused")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            error("unused")
        override suspend fun refreshSession(): RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = error("unused")
        override suspend fun signOut() = Unit
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
            error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
            error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
            error("unused")
    }
}
