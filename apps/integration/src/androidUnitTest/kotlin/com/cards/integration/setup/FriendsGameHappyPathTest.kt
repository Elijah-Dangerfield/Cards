package com.cards.integration.setup

import com.cards.integration.helpers.InProcessServer
import com.cards.integration.helpers.TestClient
import com.dangerfield.cards.features.lobby.impl.LobbyAction
import com.dangerfield.cards.features.lobby.impl.LobbyEvent
import com.dangerfield.cards.features.lobby.impl.LobbyState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden path for the multiplayer SETUP journey — driven through two REAL
 * [com.dangerfield.cards.features.lobby.impl.LobbyViewModel]s talking to one REAL
 * in-process server over real sockets:
 *
 *   host creates a room → shares the code → joiner joins by code → both land in
 *   the lobby with live presence → host starts → both navigate into the game.
 *
 * This is the "is everyone set up before the game begins?" contract — the place
 * public games (anyone with the code) and friends games (share the code)
 * converge. Asserting on real client outcomes/events (not mocks) means contract
 * drift between client and server can't ship undetected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FriendsGameHappyPathTest {

    // The view models drive their init/connection work on viewModelScope (Main),
    // so a real Main dispatcher must back it. A real (not virtual) one is required
    // because the harness uses a real socket on real threads.
    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun twoClients_createJoinPresenceStart_bothNavigate() = runBlocking<Unit> {
        InProcessServer().use { server ->
            // Host enters the "create a friends game" path: a room is created and
            // its socket opened on init.
            val host = TestClient(serverUrl = server.baseUrl, autoCreate = true)
            val code = host.vm.stateFlow.await { it.room != null }.room!!.code

            // Begin capturing the host's navigate event before it can fire.
            val hostNavigate = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    host.vm.eventFlow.filterIsInstance<LobbyEvent.NavigateToMultiplayer>().first()
                }
            }

            // Joiner enters via a shared code (the deep-link join path).
            val joiner = TestClient(serverUrl = server.baseUrl, prefilledCode = code)
            val joinerNavigate = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    joiner.vm.eventFlow.filterIsInstance<LobbyEvent.NavigateToMultiplayer>().first()
                }
            }

            // Everyone is set up: both clients see both members, both connected.
            // The host additionally reaches `canStart` (it's host + 2 seated).
            host.vm.stateFlow.await(SETUP_TIMEOUT_MS) { it.bothMembersConnected && it.canStart }
            joiner.vm.stateFlow.await(SETUP_TIMEOUT_MS) { it.bothMembersConnected }

            // Host starts the game.
            host.vm.takeAction(LobbyAction.StartGame)

            // Host navigates eagerly; the joiner auto-follows on the first
            // gameplay snapshot the server broadcasts after StartHand.
            assertEquals(code, hostNavigate.await().roomCode, "host should navigate to the room")
            assertEquals(code, joinerNavigate.await().roomCode, "joiner should auto-follow into the room")
        }
    }

    private val LobbyState.bothMembersConnected: Boolean
        get() = room?.members?.size == 2 && room!!.members.all { it.isConnected }

    private suspend fun StateFlow<LobbyState>.await(
        timeoutMs: Long = SETUP_TIMEOUT_MS,
        predicate: (LobbyState) -> Boolean,
    ): LobbyState = withTimeout(timeoutMs) { first(predicate) }

    private companion object {
        const val SETUP_TIMEOUT_MS = 20_000L
        const val EVENT_TIMEOUT_MS = 20_000L
    }
}
