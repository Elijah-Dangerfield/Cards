package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The snapshot is written at hand boundaries, not on every action.
 *
 * Writing per action put a Supabase round-trip inside the room's mutex about
 * thirty times a hand, which is what made the table serialise behind Postgres
 * (prod `state_mutate` p90 was 676ms against an engine max of 33ms). Nothing on
 * the gameplay path reads the snapshot — only hydrate and the boot recovery
 * sweep do — so the only question is which state a restart should land on, and
 * a hand boundary is the answer: chips are exact there, because nothing is
 * committed to a pot mid-flight.
 */
@OptIn(ExperimentalTime::class)
class SnapshotCheckpointTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val seats = listOf(
        SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
        SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
        SeatOccupant(seatIndex = 2, userId = "carol", displayName = "Carol", isBot = false),
    )

    /** Records every durable write so a test can count them, not just see the last one. */
    private class RecordingStore : SessionSnapshotStore {
        val writes = mutableListOf<SessionSnapshot>()
        override suspend fun upsert(snapshot: SessionSnapshot) {
            writes += snapshot
        }

        override suspend fun readByCode(code: String): SessionSnapshot? = writes.lastOrNull { it.code == code }
        override suspend fun deleteByCode(code: String) {
            writes.removeAll { it.code == code }
        }
    }

    @Test
    fun midHandActions_doNotWriteASnapshot() = runTest {
        val store = RecordingStore()
        val registry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        registry.startHand(ROOM, seats, settings)
        val afterDeal = store.writes.size

        // Three seats, so the first fold leaves two live and the hand continues.
        val state = registry.peek(ROOM)!!.state.value!!
        val acting = state.actingSeatIndex!!
        val actor = state.seats.first { it.index == acting }
        registry.applyIntent(ROOM, actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "fold-1")

        assertTrue(
            registry.peek(ROOM)!!.state.value!!.street != BettingRound.Complete,
            "precondition: this fold should not have ended the hand",
        )
        assertEquals(afterDeal, store.writes.size, "a mid-hand action must not touch the snapshot store")
    }

    @Test
    fun dealingAHand_writesACheckpoint() = runTest {
        val store = RecordingStore()
        val registry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)

        registry.startHand(ROOM, seats, settings)

        val snapshot = store.writes.lastOrNull()
        assertNotNull(snapshot, "opening a hand is a checkpoint")
        assertEquals(1, snapshot.state.handNumber)
    }

    @Test
    fun resolvingAHand_writesACheckpoint_carryingFinalStacks() = runTest {
        val store = RecordingStore()
        val registry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        registry.startHand(ROOM, seats, settings)

        var guard = 0
        while (registry.peek(ROOM)!!.state.value!!.street != BettingRound.Complete && guard++ < 10) {
            val state = registry.peek(ROOM)!!.state.value!!
            val acting = state.actingSeatIndex ?: break
            val actor = state.seats.first { it.index == acting }
            registry.applyIntent(ROOM, actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "fold-$guard")
        }

        val snapshot = store.writes.last()
        assertEquals(BettingRound.Complete, snapshot.state.street, "the resolved hand is the durable state")
        // MP-13: the sweep reads these for a seat that busted and was dropped
        // from the next deal, so they have to ride the hand-end write.
        assertTrue(snapshot.lastKnownStacks.isNotEmpty(), "hand-end checkpoint must carry lastKnownStacks")
    }

    @Test
    fun flushSnapshots_persistsMidHandState_forAGracefulShutdown() = runTest {
        val store = RecordingStore()
        val registry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        registry.startHand(ROOM, seats, settings)
        val state = registry.peek(ROOM)!!.state.value!!
        val acting = state.actingSeatIndex!!
        val actor = state.seats.first { it.index == acting }
        registry.applyIntent(ROOM, actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "fold-1")
        val live = registry.peek(ROOM)!!.state.value!!

        registry.flushSnapshots()

        // A crash rewinding a live hand to its last boundary is a fair trade; an
        // ordinary redeploy doing it is not, so shutdown takes the extra write.
        val snapshot = store.writes.last()
        assertEquals(live.actingSeatIndex, snapshot.state.actingSeatIndex)
        assertEquals(live.seats.map { it.handParticipation }, snapshot.state.seats.map { it.handParticipation })
    }

    private companion object {
        const val ROOM = "ROOM1"
    }
}
