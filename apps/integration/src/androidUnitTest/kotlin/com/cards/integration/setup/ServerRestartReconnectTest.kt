package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.Table
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.RoomConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Server restart mid-hand → full client reconnect.** Server-side hydration
 * (the registry rehydrating a session from the durable snapshot store) is pinned
 * by `SessionHydrationTest`; this is the open end-to-end half — a real client
 * whose socket drops when the process bounces reconnects to the fresh engine and
 * resumes the live hand from the persisted snapshot (MP-2).
 *
 * The harness bounces the process via [com.cards.integration.helpers.InProcessServer.restart]:
 * the netty engine (and its in-memory game registry) is dropped and rebuilt on
 * the same port, while the room membership + the session snapshot survive —
 * exactly what production keeps in Postgres. The dropped clients reconnect on
 * their own and the server rehydrates the live hand from the snapshot, so a
 * player who was mid-turn can act the moment they reconnect.
 */
class ServerRestartReconnectTest : IntegrationTest() {

    @Test
    fun restartMidHand_clientReconnectsAndResumesTheSameHand() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val handBeforeRestart = dealt.handNumber

        // Bounce the process: the netty engine + its in-memory registry are gone,
        // the durable room membership + session snapshot survive.
        server.restart()

        // Both lobby sockets reconnect to the fresh engine on their own.
        awaitReconnected(table)

        // Proof of resume: the acting player acts on the SAME hand over the
        // reconnected socket and the server accepts it — it could only do so off
        // the rehydrated state. The ack round-trips end to end (reconnect +
        // outbound drain + server apply + correlated reply).
        val acting = dealt.actingSeatIndex!!
        val owed = dealt.currentBetThisStreet - dealt.seatAt(acting).contributedThisStreet
        val intent = if (owed > 0) PlayerIntent.Call(acting) else PlayerIntent.Check(acting)
        val ack = table.gameForSeat(dealt, acting).submit(intent)
        assertTrue(ack.accepted, "an action on the rehydrated hand was accepted post-restart: ${ack.error}")

        // The post-action snapshot is still the same hand with the same seats —
        // the rehydrated table, not a fresh deal.
        val afterAction = table.hostGame.nextSnapshot { it.handNumber == handBeforeRestart }
        assertEquals(
            dealt.seats.map { it.playerId },
            afterAction.seats.map { it.playerId },
            "the rehydrated table keeps the same seats after the reconnect",
        )
    }

    /** Suspend until both clients' lobby sockets are Connected to the fresh engine. */
    private suspend fun awaitReconnected(table: Table) {
        withTimeout(RECONNECT_TIMEOUT_MS) {
            table.host.connect(table.code).connection.first { it is RoomConnection.Connected }
            table.joiner.connect(table.code).connection.first { it is RoomConnection.Connected }
        }
    }

    private companion object {
        const val RECONNECT_TIMEOUT_MS = 15_000L
    }
}
