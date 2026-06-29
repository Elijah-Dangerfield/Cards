package com.cards.integration.helpers

import com.dangerfield.cards.features.lobby.impl.LobbyEvent
import com.dangerfield.cards.features.lobby.impl.LobbyState
import com.dangerfield.cards.features.lobby.impl.LobbyViewModel
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.server.routes.DEFAULT_REAPER_GRACE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Duration

/**
 * Base class for the end-to-end integration tests. Handles the two cross-cutting
 * concerns every test needs:
 *
 *  - a REAL Main dispatcher (the view models drive init/connection work on
 *    `viewModelScope`; the harness uses real sockets on real threads, so a real —
 *    not virtual — dispatcher is required); and
 *  - a fresh in-process server per test, via [integration].
 *
 * Inside [integration], use [Harness.client] to spin up real clients and the
 * `await*` helpers to wait on real-socket state/events without fixed sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class IntegrationTest {

    @BeforeTest
    fun installRealMainDispatcher() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun restoreMainDispatcher() = Dispatchers.resetMain()

    /**
     * Boots a real [InProcessServer] and runs [block] against it. [reaperGrace]
     * is the disconnect-grace window the server schedules before freeing a
     * dropped seat — left at production's [DEFAULT_REAPER_GRACE] for most tests,
     * dropped to a sub-second window by grace-boundary (reaper) tests so the
     * timer actually fires inside the test.
     */
    protected fun integration(
        reaperGrace: Duration = DEFAULT_REAPER_GRACE,
        // The between-hands beat the server holds before auto-dealing. Tiny by
        // default so multi-hand tests run fast; a reconnect/resync test that must
        // observe the completed hand before it advances passes a larger window.
        nextHandBeatMs: Long = 50L,
        block: suspend Harness.() -> Unit,
    ) = runBlocking {
        InProcessServer(reaperGrace, nextHandBeatMs = nextHandBeatMs).use { server ->
            val harness = Harness(server)
            try {
                harness.block()
            } finally {
                harness.close()
            }
        }
    }
}

/**
 * Poll [block] until it returns true or [timeoutMs] elapses, sleeping
 * [stepMs] between tries. For asserting an *eventual* server state that has no
 * push signal to await on — e.g. a room GC'd by the reaper, observable only by
 * a fresh probe that flips from found to not-found. Prefer the push-based
 * `await*` helpers whenever a flow carries the signal.
 */
suspend fun awaitUntil(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    stepMs: Long = 25,
    block: suspend () -> Boolean,
) {
    withTimeout(timeoutMs) {
        while (!block()) delay(stepMs)
    }
}

/** The per-test surface: the running server plus factories for real clients + sessions. */
class Harness(val server: InProcessServer) {
    /** Background scope for session collectors; cancelled when the test ends. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val clients = mutableListOf<TestClient>()

    fun client(
        userId: String = randomUserId(),
        faulty: Boolean = false,
        latencyMs: Long? = null,
    ): TestClient =
        TestClient(serverUrl = server.baseUrl, userId = userId, faulty = faulty, latencyMs = latencyMs)
            .also { clients += it }

    /** A gameplay view over a live connection handle (real socket). */
    fun gameplay(handle: RoomConnectionHandle): GameplaySession =
        GameplaySession(handle, scope)

    internal fun close() {
        // Quiesce each client's reconnect loop before the server stops, so a test
        // that bounced the server doesn't leave clients hammering the recycled
        // port and tripping later tests in the same JVM.
        clients.forEach { it.close() }
        scope.cancel()
    }
}

// ---- await helpers (real time + generous timeouts; never fixed sleeps) ----

const val DEFAULT_TIMEOUT_MS = 20_000L

/** Suspend until the lobby state satisfies [predicate]; fail loudly on timeout. */
suspend fun StateFlow<LobbyState>.awaitState(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    predicate: (LobbyState) -> Boolean,
): LobbyState = withTimeout(timeoutMs) { first(predicate) }

/** The next emitted [LobbyEvent] of type [T] (events buffer, so order is safe). */
suspend inline fun <reified T : LobbyEvent> LobbyViewModel.awaitEvent(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
): T = withTimeout(timeoutMs) { eventFlow.filterIsInstance<T>().first() }

/** Assert NO [LobbyEvent] of type [T] arrives within [timeoutMs] (gating checks). */
suspend inline fun <reified T : LobbyEvent> LobbyViewModel.assertNoEvent(
    timeoutMs: Long = 1_000,
) {
    val event = withTimeoutOrNull(timeoutMs) { eventFlow.filterIsInstance<T>().first() }
    check(event == null) { "expected no ${T::class.simpleName}, but got $event" }
}

/** True when the room has exactly [n] members and all are socket-connected. */
fun LobbyState.allConnected(n: Int): Boolean =
    room?.members?.size == n && room!!.members.all { it.isConnected }
