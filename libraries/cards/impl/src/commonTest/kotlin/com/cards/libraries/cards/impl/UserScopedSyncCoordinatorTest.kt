package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.AppEvents
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the level-based trigger wiring every [UserScopedSyncer] relies on:
 * real [SyncTriggers] over fake auth / events / connectivity, so the
 * boot-ordering guarantees are tested end to end rather than per-flow.
 */
class UserScopedSyncCoordinatorTest : CoroutineTest() {

    @Test
    fun authResolvedAndBootEventsFired_beforeCoordinatorConstructs_stillSyncsOnce() = runUnitTest {
        // The 07-09 prod incident: a fast launch resolved auth and fired the
        // boot events before the coordinator subscribed, and the sign-in edge
        // was gone. Against a level, late subscription must not matter.
        val f = fixture(construct = false)
        f.auth.emit(authenticated("u1"))
        f.bus.dispatch(AppEvent.ColdBoot)
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))

        f.construct()
        runCurrent()

        assertEquals(1, f.a.syncs)
        assertEquals(1, f.b.syncs)
    }

    @Test
    fun coldBootWithRestoredSession_eventsThenResolve_syncsOnce() = runUnitTest {
        val f = fixture()
        f.bus.dispatch(AppEvent.ColdBoot)
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))
        runCurrent()
        assertEquals(0, f.a.syncs, "nothing to sync before auth resolves")

        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)
        assertEquals(1, f.b.syncs)
    }

    @Test
    fun freshInstallUnauthenticated_neverSyncs_untilGuestHealFlipsAuth() = runUnitTest {
        val f = fixture()
        f.auth.emit(AuthState.Unauthenticated())
        f.bus.dispatch(AppEvent.ColdBoot)
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals(0, f.a.syncs, "no session — nothing to reconcile, warm fg included")

        f.auth.emit(authenticated("guest-1"))
        runCurrent()
        assertEquals(1, f.a.syncs)
    }

    @Test
    fun signOutMidBackoff_stopsRetries() = runUnitTest {
        val f = fixture()
        f.a.result = Result.failure(RuntimeException("server down"))
        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)

        f.auth.emit(AuthState.Unauthenticated())
        runCurrent()
        advanceTimeBy(300.seconds)
        assertEquals(1, f.a.syncs, "sign-out cancelled the pending backoff")
    }

    @Test
    fun userSwitch_cancelsInFlightSync_andSyncsTheNewUser() = runUnitTest {
        val f = fixture()
        f.a.gateNextSync()
        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)
        assertEquals(0, f.a.completedSyncs, "u1's sync is parked in-flight")

        f.auth.emit(authenticated("u2"))
        runCurrent()
        assertEquals(2, f.a.syncs, "the switch fired a fresh sync for u2")
        f.a.openGate()
        runCurrent()
        assertEquals(1, f.a.completedSyncs, "u2's ran to completion; u1's was cancelled, not completed")
    }

    @Test
    fun claim_sameIdAnonymousFlips_refires_withNoClaimEventOnTheBus() = runUnitTest {
        val f = fixture()
        f.auth.emit(authenticated("guest-1", isAnonymous = true))
        runCurrent()
        assertEquals(1, f.a.syncs)

        f.auth.emit(authenticated("guest-1", isAnonymous = false))
        runCurrent()
        assertEquals(2, f.a.syncs, "the isAnonymous flip is a key change — no bus event needed")
    }

    @Test
    fun warmForegroundRefiresOnlyWhileAuthed_coldForegroundNeverDoubleFires() = runUnitTest {
        val f = fixture()
        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)

        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))
        runCurrent()
        assertEquals(1, f.a.syncs, "cold-boot foreground is owned by the auth level")

        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals(2, f.a.syncs)

        f.auth.emit(AuthState.Unauthenticated())
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals(2, f.a.syncs, "after sign-out a warm resume has nothing to sync")
    }

    @Test
    fun connectivityRegained_pastDebounce_refires_flapInsideWindowFiresOnce() = runUnitTest {
        val f = fixture()
        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)

        f.appState.isOffline.value = true
        f.appState.isOffline.value = false
        advanceTimeBy(1.seconds)
        assertEquals(2, f.a.syncs, "offline→online past the debounce refires")

        f.appState.isOffline.value = true
        advanceTimeBy(0.2.seconds)
        f.appState.isOffline.value = false
        advanceTimeBy(0.2.seconds)
        f.appState.isOffline.value = true
        advanceTimeBy(0.2.seconds)
        f.appState.isOffline.value = false
        advanceTimeBy(1.seconds)
        assertEquals(3, f.a.syncs, "a flap inside the window collapses to one refire")
    }

    @Test
    fun oneFailingSyncerRetriesAlone_theOtherRunsOnce() = runUnitTest {
        val f = fixture()
        f.a.result = Result.failure(RuntimeException("wallet 500"))
        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)
        assertEquals(1, f.b.syncs)

        advanceTimeBy(6.seconds)
        assertEquals(2, f.a.syncs, "the failing syncer retried on its own schedule")
        assertEquals(1, f.b.syncs, "the healthy syncer did not re-run")

        advanceTimeBy(12.seconds)
        assertEquals(3, f.a.syncs)
        assertEquals(1, f.b.syncs)
    }

    @Test
    fun foregroundSpamDuringSlowSync_coalescesToOneTrailingRun() = runUnitTest {
        val f = fixture()
        f.a.gateNextSync()
        f.auth.emit(authenticated("u1"))
        runCurrent()
        assertEquals(1, f.a.syncs)

        repeat(3) { f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false)) }
        runCurrent()
        assertEquals(1, f.a.syncs, "still inside the slow first sync")

        f.a.openGate()
        runCurrent()
        assertEquals(2, f.a.syncs, "three foregrounds coalesced into one trailing sync")
    }

    // ---------- scaffolding ----------

    private fun TestScope.fixture(construct: Boolean = true): Fixture {
        val fixture = Fixture(
            bus = FakeBus(),
            auth = FakeAuthRepo(),
            appState = FakeAppState(),
            a = RecordingSyncer(),
            b = RecordingSyncer(),
            appScope = AppCoroutineScope(dispatchers),
        )
        if (construct) fixture.construct()
        return fixture
    }

    private class Fixture(
        val bus: FakeBus,
        val auth: FakeAuthRepo,
        val appState: FakeAppState,
        val a: RecordingSyncer,
        val b: RecordingSyncer,
        private val appScope: AppCoroutineScope,
    ) {
        fun construct() {
            UserScopedSyncCoordinator(
                triggers = SyncTriggers(
                    authRepositoryProvider = { auth },
                    appEventsProvider = { AppEvents(bus) },
                    appState = appState,
                ),
                syncers = setOf(a, b),
                appScope = appScope,
            )
        }
    }

    private fun authenticated(userId: String, isAnonymous: Boolean = true) =
        AuthState.Authenticated(userId = userId, isAnonymous = isAnonymous, email = null)

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

    private class RecordingSyncer : UserScopedSyncer {
        var syncs = 0
            private set
        var completedSyncs = 0
            private set
        var result: Result<Unit> = Result.success(Unit)
        private var gate: CompletableDeferred<Unit>? = null

        fun gateNextSync() {
            gate = CompletableDeferred()
        }

        fun openGate() {
            gate?.complete(Unit)
            gate = null
        }

        override suspend fun sync(): Result<Unit> {
            syncs++
            gate?.await()
            completedSyncs++
            return result
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
