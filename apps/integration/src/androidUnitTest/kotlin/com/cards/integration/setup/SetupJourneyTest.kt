package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.allConnected
import com.cards.integration.helpers.awaitEvent
import com.cards.integration.helpers.awaitState
import com.cards.integration.helpers.assertNoEvent
import com.dangerfield.cards.features.lobby.impl.LobbyAction
import com.dangerfield.cards.features.lobby.impl.LobbyError
import com.dangerfield.cards.features.lobby.impl.LobbyEvent
import com.dangerfield.cards.features.lobby.impl.LobbyState
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The multiplayer SETUP journey beyond the happy path — the cases that decide
 * whether "everyone is set up before the game begins": bad joins, a full room,
 * presence + host election when sockets drop, reconnect, and start-gating. All
 * driven through the REAL `LobbyViewModel` + REAL client stack against the REAL
 * server, so these assert production behaviour, not fakes.
 */
class SetupJourneyTest : IntegrationTest() {

    // ---- Join failures surface as typed lobby errors ----

    @Test
    fun join_unknownCode_bouncesBackToCodeEntry() = integration {
        // Deep-link join path: the lobby opens with the code populated and joins.
        // A bad prefilled code bounces back to the code-entry screen (a
        // JoinCodeRejected event) rather than stranding the player on the dead
        // lobby with an inline error, so the inline error stays null.
        val joiner = client().lobbyVm(prefilledCode = "NOPE12")

        val rejected = joiner.awaitEvent<LobbyEvent.JoinCodeRejected>()
        assertEquals("NOPE12", rejected.code)
        assertEquals(null, joiner.state.room)
        assertEquals(null, joiner.state.error)
    }

    @Test
    fun join_fullRoom_surfacesFull() = integration {
        // A 2-seat room filled by host (seat 0) + one filler (seat 1).
        val host = client()
        val created = host.repository.createRoom(maxSeats = 2)
        assertTrue(created is CreateRoomOutcome.Success)
        val code = created.room.code
        assertTrue(client().repository.joinRoom(code) is JoinRoomOutcome.Success)

        // A third player's lobby tries to join the now-full room.
        val latecomer = client().lobbyVm(prefilledCode = code)
        val state = latecomer.stateFlow.awaitState { it.error != null }
        assertTrue(state.error is LobbyError.JoinRoomFull, "got ${state.error}")
    }

    @Test
    fun join_alreadyAMember_isIdempotent() = integration {
        val host = client()
        val created = host.repository.createRoom()
        assertTrue(created is CreateRoomOutcome.Success)

        val rejoin = host.repository.joinRoom(created.room.code)
        assertTrue(rejoin is JoinRoomOutcome.Success && rejoin.alreadyJoined, "got $rejoin")
    }

    // ---- Presence convergence ----

    @Test
    fun fourPlayers_allConvergeInLobby_allConnected() = integration {
        val hostVm = client().lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code

        val joiners = List(3) { client().lobbyVm(prefilledCode = code) }

        hostVm.stateFlow.awaitState { it.allConnected(4) }
        joiners.forEach { it.stateFlow.awaitState { state -> state.allConnected(4) } }
    }

    // ---- Start gating ----

    @Test
    fun hostAlone_startGame_isNoOp() = integration {
        val hostVm = client().lobbyVm(autoCreate = true)
        hostVm.stateFlow.awaitState { it.allConnected(1) }
        assertEquals(false, hostVm.state.canStart)

        hostVm.takeAction(LobbyAction.StartGame)
        hostVm.assertNoEvent<LobbyEvent.NavigateToMultiplayer>()
    }

    @Test
    fun nonHost_startGame_isNoOp() = integration {
        val host = client()
        val joiner = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val joinerVm = joiner.lobbyVm(prefilledCode = code)
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        // The joiner is not the host — Start must not fire navigation.
        assertEquals(false, joinerVm.state.canStart)
        joinerVm.takeAction(LobbyAction.StartGame)
        joinerVm.assertNoEvent<LobbyEvent.NavigateToMultiplayer>()
    }

    // ---- Leave ----

    @Test
    fun playerLeaves_othersSeeMemberDrop() = integration {
        val host = client()
        val joiner = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val joinerVm = joiner.lobbyVm(prefilledCode = code)
        hostVm.stateFlow.awaitState { it.allConnected(2) }

        joinerVm.takeAction(LobbyAction.Leave)

        hostVm.stateFlow.awaitState { it.room?.members?.size == 1 }
    }

    // ---- Disconnect → host election, and reconnect ----

    @Test
    fun hostDisconnects_joinerIsPromoted() = integration {
        val host = client(faulty = true)
        val joiner = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val joinerVm = joiner.lobbyVm(prefilledCode = code)
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        // Host's socket drops and stays down — the server flips its presence.
        host.faults!!.dropAndBlock()

        val promotion = joinerVm.awaitEvent<LobbyEvent.HostPromoted>()
        assertTrue(promotion.isLocalUser, "the joiner should become the effective host")
        assertEquals(joiner.userId, joinerVm.state.effectiveHostUserId)
    }

    @Test
    fun hostDropsAndReconnectsFast_bothClientsAgreeOnExactlyOneHost() = integration {
        val host = client(faulty = true)
        val joiner = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val joinerVm = joiner.lobbyVm(prefilledCode = code)
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        // Host drops: the joiner takes over as effective host.
        host.faults!!.dropAndBlock()
        joinerVm.awaitEvent<LobbyEvent.HostPromoted>()
        assertEquals(joiner.userId, joinerVm.state.effectiveHostUserId)

        // Host reconnects before the grace reaper frees its seat — the race that
        // could momentarily look like "two hosts". Both clients must reconverge on
        // a single effective host (the original), never disagree.
        host.faults!!.blockReconnects = false
        hostVm.stateFlow.awaitState { it.allConnected(2) }
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        joinerVm.stateFlow.awaitState { it.effectiveHostUserId == host.userId }
        hostVm.stateFlow.awaitState { it.effectiveHostUserId == host.userId }
        assertEquals(
            hostVm.state.effectiveHostUserId,
            joinerVm.state.effectiveHostUserId,
            "both clients must agree on exactly one effective host after the reconnect race",
        )
    }

    @Test
    fun droppedClient_reconnects_andPresenceIsRestored() = integration {
        val host = client(faulty = true)
        val joiner = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val joinerVm = joiner.lobbyVm(prefilledCode = code)
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        // Drop + block so the disconnect is observable, then allow recovery.
        host.faults!!.dropAndBlock()
        joinerVm.stateFlow.awaitState { it.memberConnected(host.userId) == false }

        host.faults!!.blockReconnects = false
        joinerVm.stateFlow.awaitState { it.allConnected(2) }
    }

    private fun LobbyState.memberConnected(userId: String): Boolean? =
        room?.members?.firstOrNull { it.userId == userId }?.isConnected
}
