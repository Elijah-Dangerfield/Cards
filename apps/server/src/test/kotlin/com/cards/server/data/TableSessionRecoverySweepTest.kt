package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.TableSessionsTable
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.game.NoOpSessionSnapshotStore
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the boot recovery sweep. After a (simulated) crash,
 * any open table session is abandoned — its in-memory room is gone — so the
 * sweep refunds the stranded buy-in from the last durable snapshot. With no
 * snapshot the player is refunded the full funded amount (net zero), and the
 * sweep is idempotent (a re-run settles nothing already settled).
 */
@OptIn(ExperimentalTime::class)
class TableSessionRecoverySweepTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            TableSessionsTable.deleteAll()
            WalletEventsTable.deleteAll()
            WalletsTable.deleteAll()
        }
    }

    @Test
    fun sweep_refundsAbandonedSession_andClosesIt() = runTest {
        val user = newUser()
        newService().sitDown(user, ROOM, CASUAL_BUY_IN) // −1000, balance 9000, session open

        val settled = newSweep().sweepAbandonedSessions()

        assertEquals(1, settled)
        // No snapshot → full funded refund → net zero, session closed.
        assertEquals(Wallet.STARTER_GRANT, newWallets().findOrCreate(user).balance)
        assertNull(newTableSessions().findActiveForUser(user))
    }

    @Test
    fun sweep_isIdempotent_secondRunSettlesNothing() = runTest {
        val user = newUser()
        newService().sitDown(user, ROOM, CASUAL_BUY_IN)
        newSweep().sweepAbandonedSessions()

        val secondRun = newSweep().sweepAbandonedSessions()

        assertEquals(0, secondRun)
        assertEquals(Wallet.STARTER_GRANT, newWallets().findOrCreate(user).balance)
    }

    @Test
    fun sweep_withNoActiveSessions_returnsZero() = runTest {
        assertEquals(0, newSweep().sweepAbandonedSessions())
    }

    private fun newSweep(clock: Clock = Clock.System) = DefaultTableSessionRecoverySweep(
        tableSessions = PostgresTableSessionRepository(database, clock),
        tableSessionService = newService(clock),
        snapshots = NoOpSessionSnapshotStore(),
    )

    private fun newService(clock: Clock = Clock.System) = DefaultTableSessionService(
        database = database,
        tableSessions = PostgresTableSessionRepository(database, clock),
        wallets = PostgresWalletRepository(database, clock),
        clock = clock,
    )

    private fun newWallets(clock: Clock = Clock.System) = PostgresWalletRepository(database, clock)
    private fun newTableSessions(clock: Clock = Clock.System) = PostgresTableSessionRepository(database, clock)

    private fun newUser(): UserId = seedAuthUser()

    private companion object {
        const val ROOM = "SWP234"
        const val CASUAL_BUY_IN = 1_000L
    }
}
