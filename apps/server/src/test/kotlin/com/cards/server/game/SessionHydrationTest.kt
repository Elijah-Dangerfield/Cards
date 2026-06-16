package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.RoomSessionsTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The persistence payoff: after a hand is started and persisted, a **fresh**
 * [DefaultGameSessionRegistry] — an empty in-memory map backed by the same real
 * Postgres store, i.e. a server restart — rebuilds the live session by hydrating
 * its [SessionSnapshot] from the `room_sessions` table.
 *
 * The existing registry integration tests use an in-memory `NoOp` store; this is
 * the one that proves the durable read-back against real Postgres.
 */
@OptIn(ExperimentalTime::class)
class SessionHydrationTest : DatabaseTest() {

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
        database.blockingTransaction { RoomSessionsTable.deleteAll() }
    }

    @Test
    fun startHand_thenFreshRegistry_hydratesLiveStateFromPostgres() = runTest {
        val store = PostgresSessionSnapshotStore(database)

        // A hand is started + persisted by the original (pre-restart) registry.
        val original = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        original.startHand("ROOM_HYD", listOf(alice, bob), settings)
        val livePreRestart = original.peek("ROOM_HYD")!!.state.value!!

        // A brand-new registry — cleared in-memory map, same durable store — is the
        // post-restart process. It knows nothing until it hydrates from Postgres.
        val afterRestart = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        assertNull(afterRestart.peek("ROOM_HYD"), "a fresh registry has no in-memory session")

        val hydrated = afterRestart.findOrHydrate("ROOM_HYD")
        assertNotNull(hydrated, "the session should hydrate from Postgres after a restart")

        val state = hydrated.state.value!!
        assertEquals(livePreRestart.handNumber, state.handNumber)
        assertEquals(livePreRestart.street, state.street)
        assertEquals(livePreRestart.actingSeatIndex, state.actingSeatIndex)
        assertEquals(listOf("alice", "bob"), state.seats.map { it.playerId })
    }
}
