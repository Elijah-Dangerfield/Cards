package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.allConnected
import com.cards.integration.helpers.awaitEvent
import com.cards.integration.helpers.awaitState
import com.dangerfield.cards.features.lobby.impl.LobbyAction
import com.dangerfield.cards.features.lobby.impl.LobbyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden path for the multiplayer SETUP journey, through two REAL
 * `LobbyViewModel`s talking to one REAL server over real sockets:
 *
 *   host creates a room → shares the code → joiner joins by code → both land in
 *   the lobby with live presence → host starts → both navigate into the game.
 *
 * This is the "is everyone set up before the game begins?" contract — where
 * public games (anyone with the code) and friends games (share the code)
 * converge.
 */
class FriendsGameHappyPathTest : IntegrationTest() {

    @Test
    fun twoClients_createJoinPresenceStart_bothNavigate() = integration {
        val host = client()
        val joiner = client()

        // Host enters the "create a friends game" path; a room + socket open on init.
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code

        // Joiner enters via the shared code (the deep-link join path).
        val joinerVm = joiner.lobbyVm(prefilledCode = code)

        // Everyone is set up: both clients see both members, both connected; the
        // host additionally reaches `canStart` (it's host + 2 seated).
        hostVm.stateFlow.awaitState { it.allConnected(2) && it.canStart }
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        hostVm.takeAction(LobbyAction.StartGame)

        // Host navigates eagerly; the joiner auto-follows on the first gameplay
        // snapshot the server broadcasts after StartHand.
        assertEquals(code, hostVm.awaitEvent<LobbyEvent.NavigateToMultiplayer>().roomCode)
        assertEquals(code, joinerVm.awaitEvent<LobbyEvent.NavigateToMultiplayer>().roomCode)
    }
}
