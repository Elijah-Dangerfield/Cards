package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.RoomSessionsTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for [PostgresSessionSnapshotStore]. Drives a real
 * `GameSession` through `startHand` + a fold and asserts the durable
 * row matches the engine's resulting state via the kotlinx-serialization
 * round-trip path.
 */
@OptIn(ExperimentalTime::class)
class PostgresSessionSnapshotStoreTest : DatabaseTest() {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
    private val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

    @After
    fun cleanTables() {
        database.blockingTransaction {
            RoomSessionsTable.deleteAll()
        }
    }

    @Test
    fun readByCode_returnsNull_whenNoRowExists() = runTest {
        val store = PostgresSessionSnapshotStore(database)
        assertNull(store.readByCode("ROOM_NONE"))
    }

    @Test
    fun upsert_thenReadByCode_roundTripsState() = runTest {
        val registry = DefaultGameSessionRegistry(
            snapshotStore = PostgresSessionSnapshotStore(database),
            clock = Clock.System,
        )

        registry.startHand("ROOM_RT", listOf(alice, bob), settings)

        val store = PostgresSessionSnapshotStore(database)
        val snapshot = store.readByCode("ROOM_RT")
        assertNotNull(snapshot)
        assertEquals("ROOM_RT", snapshot.code)
        val state = snapshot.state
        assertEquals(1, state.handNumber)
        assertEquals(BettingRound.Preflop, state.street)
        assertEquals(2, state.seats.size)
        assertEquals("alice", state.seats[0].playerId)
        assertEquals("bob", state.seats[1].playerId)
    }

    @Test
    fun upsert_overwritesExistingRow_acrossMutations() = runTest {
        val registry = DefaultGameSessionRegistry(
            snapshotStore = PostgresSessionSnapshotStore(database),
            clock = Clock.System,
        )

        registry.startHand("ROOM_OV", listOf(alice, bob), settings)
        val session = registry.peek("ROOM_OV")!!
        val state = session.state.value!!
        val acting = state.seats.first { it.index == state.actingSeatIndex }
        registry.applyIntent("ROOM_OV", acting.playerId!!, PlayerIntent.Fold(seatIndex = acting.index), "fold-1")

        val store = PostgresSessionSnapshotStore(database)
        val snapshot = store.readByCode("ROOM_OV")
        assertNotNull(snapshot)
        assertEquals(BettingRound.Complete, snapshot.state.street)

        val rowCount: Long = database.blockingTransaction {
            RoomSessionsTable.selectAll().where {
                RoomSessionsTable.roomCode eq "ROOM_OV"
            }.count()
        }
        assertEquals(1L, rowCount)
    }

    @Test
    fun deleteByCode_removesRow() = runTest {
        val store = PostgresSessionSnapshotStore(database)
        val registry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        registry.startHand("ROOM_DEL", listOf(alice, bob), settings)
        assertNotNull(store.readByCode("ROOM_DEL"))

        store.deleteByCode("ROOM_DEL")

        assertNull(store.readByCode("ROOM_DEL"))
    }

    @Test
    fun registry_hydratesFromSnapshot_acrossRestart() = runTest {
        val store = PostgresSessionSnapshotStore(database)

        val first = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        val started = first.startHand("ROOM_REH", listOf(alice, bob), settings)
        assertIs<IntentResult.Accepted>(started)
        val originalSessionId = first.peek("ROOM_REH")!!.id
        val originalState = first.peek("ROOM_REH")!!.state.value!!

        // Simulate server restart by spinning up a fresh registry against
        // the same store. Nothing in memory; hydration must rebuild from
        // the durable row.
        val second = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        assertNull(second.peek("ROOM_REH"))

        val hydrated = second.findOrHydrate("ROOM_REH")
        assertNotNull(hydrated)
        assertEquals(originalSessionId, hydrated.id)
        val hydratedState = hydrated.state.value!!
        assertEquals(originalState.handNumber, hydratedState.handNumber)
        assertEquals(originalState.seats.map { it.playerId }, hydratedState.seats.map { it.playerId })
        assertEquals(originalState.street, hydratedState.street)
    }

    @Test
    fun registry_end_dropsDurableRow() = runTest {
        val store = PostgresSessionSnapshotStore(database)
        val registry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        registry.startHand("ROOM_END", listOf(alice, bob), settings)
        assertNotNull(store.readByCode("ROOM_END"))

        registry.end("ROOM_END")

        assertNull(store.readByCode("ROOM_END"))
    }
}
