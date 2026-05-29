package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives a real hand through [InMemoryGameSessionRegistry] end-to-end:
 * register → start → observe state appearing → fold-to-complete → next
 * hand. This is the smoke test for "the registry actually behaves
 * like the socket route will call it"; the socket route adds its own
 * integration tests on top.
 */
class GameSessionRegistryIntegrationTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
    private val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

    @Test
    fun observeSession_emitsNull_beforeStart() = runTest {
        val registry = InMemoryGameSessionRegistry()

        assertNull(registry.observeSession("ROOM1").first())
    }

    @Test
    fun observeSession_emitsSession_afterStart() = runTest {
        val registry = InMemoryGameSessionRegistry()

        registry.startHand("ROOM1", listOf(alice, bob), settings)
        val session = registry.observeSession("ROOM1").first()

        assertNotNull(session)
        assertEquals(session, registry.peek("ROOM1"))
    }

    @Test
    fun observeSession_isCodeScoped() = runTest {
        val registry = InMemoryGameSessionRegistry()

        registry.startHand("ROOM_A", listOf(alice, bob), settings)

        val other = registry.observeSession("ROOM_B").firstOrNull()
        assertNull(other)
        val mine = registry.observeSession("ROOM_A").first()
        assertNotNull(mine)
    }

    @Test
    fun fullHand_fold_drivesStateToComplete_through_registry() = runTest {
        val registry = InMemoryGameSessionRegistry()

        val started = registry.startHand("ROOM1", listOf(alice, bob), settings)
        assertIs<IntentResult.Accepted>(started)

        val session = registry.peek("ROOM1")!!
        val state = session.state.value!!
        val actingSeat = state.seats.first { it.index == state.actingSeatIndex }
        val actingUser = actingSeat.playerId!!

        val foldResult = registry.applyIntent(
            code = "ROOM1",
            actorUserId = actingUser,
            intent = PlayerIntent.Fold(seatIndex = actingSeat.index),
            clientNonce = "fold-1",
        )
        assertIs<IntentResult.Accepted>(foldResult)

        assertEquals(BettingRound.Complete, session.state.value!!.street)
    }

    @Test
    fun applyIntent_unknownCode_isRejected() = runTest {
        val registry = InMemoryGameSessionRegistry()

        val result = registry.applyIntent(
            code = "GHOST",
            actorUserId = "alice",
            intent = PlayerIntent.Fold(seatIndex = 0),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Rejected>(result)
        assertEquals("no game session for room GHOST", result.reason)
    }

    @Test
    fun end_dropsSession_andSubsequentLookupReturnsNull() = runTest {
        val registry = InMemoryGameSessionRegistry()
        registry.startHand("ROOM1", listOf(alice, bob), settings)
        assertNotNull(registry.peek("ROOM1"))

        registry.end("ROOM1")

        assertNull(registry.peek("ROOM1"))
        assertNull(registry.observeSession("ROOM1").first())
    }

    @Test
    fun nextHand_continues_after_completion() = runTest {
        val registry = InMemoryGameSessionRegistry()
        registry.startHand("ROOM1", listOf(alice, bob), settings)

        val session = registry.peek("ROOM1")!!
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }
        registry.applyIntent("ROOM1", actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")
        assertEquals(BettingRound.Complete, session.state.value!!.street)

        val next = registry.requestNextHand("ROOM1", actor.playerId!!, "n2")
        assertIs<IntentResult.Accepted>(next)
        assertEquals(2, session.state.value!!.handNumber)
        assertEquals(BettingRound.Preflop, session.state.value!!.street)
    }
}
