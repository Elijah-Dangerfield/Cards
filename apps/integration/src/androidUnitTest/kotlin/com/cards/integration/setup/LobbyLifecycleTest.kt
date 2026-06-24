package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.allConnected
import com.cards.integration.helpers.awaitState
import com.dangerfield.cards.features.lobby.impl.LobbyAction
import com.dangerfield.cards.features.lobby.impl.LobbyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Lobby presence + host-promotion through the real `LobbyViewModel`.** Beyond
 * SetupJourneyTest's single-event checks: a promotion that re-enables the Start
 * CTA, and presence staying consistent across repeated drop/reconnect flaps.
 */
class LobbyLifecycleTest : IntegrationTest() {

    @Test
    fun hostLeaves_promotedMemberCanStart() = integration {
        val host = client()
        val a = client()
        val b = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val aVm = a.lobbyVm(prefilledCode = code)
        val bVm = b.lobbyVm(prefilledCode = code)
        aVm.stateFlow.awaitState { it.allConnected(3) }
        bVm.stateFlow.awaitState { it.allConnected(3) }

        // Before promotion, a is not the host and can't start.
        assertEquals(false, aVm.state.canStart, "a non-host can't start")

        // The host leaves; a (longest-tenured remaining) becomes effective host
        // and — with two members still seated — the Start CTA enables.
        hostVm.takeAction(LobbyAction.Leave)

        aVm.stateFlow.awaitState { it.effectiveHostUserId == a.userId && it.canStart }
        assertEquals(a.userId, aVm.state.effectiveHostUserId, "a is promoted to host")
        assertTrue(aVm.state.canStart, "the promoted host can start now that two remain")
    }

    @Test
    fun presenceFlapsRepeatedly_convergesConnectedEachTime() = integration {
        val host = client(faulty = true)
        val joiner = client()
        val hostVm = host.lobbyVm(autoCreate = true)
        val code = hostVm.stateFlow.awaitState { it.room != null }.room!!.code
        val joinerVm = joiner.lobbyVm(prefilledCode = code)
        joinerVm.stateFlow.awaitState { it.allConnected(2) }

        // Three drop/reconnect cycles — presence must report the drop each time
        // and reconverge to all-connected, never drifting or double-counting.
        repeat(3) {
            host.faults!!.dropAndBlock()
            joinerVm.stateFlow.awaitState { it.memberConnected(host.userId) == false }

            host.faults!!.blockReconnects = false
            joinerVm.stateFlow.awaitState { it.allConnected(2) }
        }

        assertTrue(joinerVm.state.allConnected(2), "the table is whole after repeated flaps")
        assertEquals(2, joinerVm.state.room?.members?.size, "no phantom members from the flaps")
    }

    private fun LobbyState.memberConnected(userId: String): Boolean? =
        room?.members?.firstOrNull { it.userId == userId }?.isConnected
}
