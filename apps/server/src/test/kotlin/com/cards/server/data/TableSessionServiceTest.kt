package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.gameplay.StakeTier
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.TableSessionsTable
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.CashOutResult
import com.dangerfield.cards.server.domain.RebuyResult
import com.dangerfield.cards.server.domain.SitDownResult
import com.dangerfield.cards.server.domain.TableSessionStatus
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the multiplayer chip economy: buy-in → rebuy →
 * cash-out moving real wallet chips through the shared ledger, against a
 * real Postgres (so the partial unique double-spend index and the
 * `(user_id, idempotency_key)` dedup are exercised, not mocked).
 *
 * The headline guarantees under test: a full sit nets to
 * `cashout − buyin − Σrebuys`, and a cash-out interrupted after the
 * status flip credits exactly once when resumed (the crash-recovery path
 * a boot sweep relies on).
 */
@OptIn(ExperimentalTime::class)
class TableSessionServiceTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            TableSessionsTable.deleteAll()
            WalletEventsTable.deleteAll()
            WalletsTable.deleteAll()
        }
    }

    @Test
    fun sitDown_realTier_debitsBuyIn_andOpensSession() = runTest {
        val service = newService()
        val user = newUser()

        val result = service.sitDown(user, ROOM, StakeTier.Casual)

        assertTrue(result is SitDownResult.Funded, "expected Funded, was $result")
        assertEquals(StakeTier.Casual.buyIn, result.startingStack)
        assertEquals(Wallet.STARTER_GRANT - StakeTier.Casual.buyIn, result.balanceAfter)

        val session = newTableSessions().findActiveForUser(user)
        assertEquals(StakeTier.Casual.buyIn, session?.buyIn)
        assertEquals(TableSessionStatus.Open, session?.status)
        assertEquals(0, session?.rebuyCount)

        val events = newWallets().recentEvents(user, 10)
        assertEquals(listOf("mp_buyin"), events.map { it.reason })
        assertEquals(-StakeTier.Casual.buyIn, events.single().delta)
    }

    @Test
    fun sitDown_practiceTier_isFreePlay_noWalletMovement() = runTest {
        val service = newService()
        val user = newUser()

        val result = service.sitDown(user, ROOM, StakeTier.Practice)

        assertTrue(result is SitDownResult.FreePlay, "expected FreePlay, was $result")
        assertEquals(StakeTier.Practice.buyIn, result.startingStack)
        // No durable session, and the wallet was never even lazy-created.
        assertNull(newTableSessions().findActiveForUser(user))
        assertNull(newWallets().find(user))
    }

    @Test
    fun sitDown_belowEntryBar_whenBuyInExceeds25PercentOfWallet() = runTest {
        val service = newService()
        val user = newUser()

        // Starter grant is 10k; Standard buy-in is 5k, which needs a 20k
        // wallet to clear the 25% bar.
        val result = service.sitDown(user, ROOM, StakeTier.Standard)

        assertTrue(result is SitDownResult.BelowEntryBar, "expected BelowEntryBar, was $result")
        assertEquals(Wallet.STARTER_GRANT, result.balance)
        assertEquals(StakeTier.Standard.buyIn * 4, result.minBalance)
        // Rejected before any movement: no row, no debit.
        assertNull(newTableSessions().findActiveForUser(user))
        assertEquals(0, newWallets().recentEvents(user, 10).size)
    }

    @Test
    fun sitDown_whenAlreadySeated_returnsAlreadyAtTable_andDoesNotDoubleDebit() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, StakeTier.Casual)

        val second = service.sitDown(user, "ZZZZZZ", StakeTier.Casual)

        assertTrue(second is SitDownResult.AlreadyAtTable, "expected AlreadyAtTable, was $second")
        assertEquals(ROOM, second.roomCode)
        // Still exactly one buy-in debit.
        assertEquals(Wallet.STARTER_GRANT - StakeTier.Casual.buyIn, newWallets().findOrCreate(user).balance)
        assertEquals(1, newWallets().recentEvents(user, 10).count { it.reason == "mp_buyin" })
    }

    @Test
    fun cashOut_creditsFinalStack_andClosesSession() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, StakeTier.Casual)

        val result = service.cashOut(user, finalStack = 1_500)

        assertTrue(result is CashOutResult.CashedOut, "expected CashedOut, was $result")
        assertEquals(1_500, result.refunded)
        // 10000 − 1000 buy-in + 1500 cash-out = 10500.
        assertEquals(Wallet.STARTER_GRANT - StakeTier.Casual.buyIn + 1_500, result.balanceAfter)
        assertNull(newTableSessions().findActiveForUser(user))
    }

    @Test
    fun cashOut_withNoActiveSession_isNoOp() = runTest {
        val service = newService()
        val user = newUser()

        assertEquals(CashOutResult.NoActiveSession, service.cashOut(user, finalStack = 999))
    }

    @Test
    fun rebuy_debitsAnotherBuyIn_andCountsUp() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, StakeTier.Casual)

        val result = service.rebuy(user)

        assertTrue(result is RebuyResult.ReboughtIn, "expected ReboughtIn, was $result")
        assertEquals(StakeTier.Casual.buyIn, result.startingStack)
        assertEquals(Wallet.STARTER_GRANT - 2 * StakeTier.Casual.buyIn, result.balanceAfter)
        assertEquals(1, newTableSessions().findActiveForUser(user)?.rebuyCount)
        assertEquals(1, newWallets().recentEvents(user, 10).count { it.reason == "mp_rebuy" })
    }

    @Test
    fun fullSit_ledgerNetsToProfitAndLoss() = runTest {
        val service = newService()
        val user = newUser()

        service.sitDown(user, ROOM, StakeTier.Casual) // −1000
        service.rebuy(user)                            // −1000
        val cashOut = service.cashOut(user, finalStack = 3_000) // +3000

        // Net session P/L = +1000 → 10000 → 11000.
        assertTrue(cashOut is CashOutResult.CashedOut)
        assertEquals(Wallet.STARTER_GRANT + 1_000, cashOut.balanceAfter)
        assertEquals(Wallet.STARTER_GRANT + 1_000, newWallets().findOrCreate(user).balance)

        val reasons = newWallets().recentEvents(user, 10).map { it.reason }.toSet()
        assertEquals(setOf("mp_buyin", "mp_rebuy", "mp_cashout"), reasons)
    }

    @Test
    fun cashOut_resumingFromClosing_creditsExactlyOnce() = runTest {
        // Simulates a crash mid-cash-out: the status flipped to `closing` but
        // the credit never landed. A boot sweep re-runs cash-out; the keyed,
        // idempotent credit + forward-only status must settle exactly once.
        val service = newService()
        val tableSessions = newTableSessions()
        val user = newUser()
        val funded = service.sitDown(user, ROOM, StakeTier.Casual) as SitDownResult.Funded
        tableSessions.markClosing(funded.sessionId)

        val first = service.cashOut(user, finalStack = 1_500)
        val second = service.cashOut(user, finalStack = 1_500)

        assertTrue(first is CashOutResult.CashedOut)
        assertEquals(Wallet.STARTER_GRANT - StakeTier.Casual.buyIn + 1_500, first.balanceAfter)
        // Session is closed now → the resumed call is a no-op, no second credit.
        assertEquals(CashOutResult.NoActiveSession, second)
        assertEquals(1, newWallets().recentEvents(user, 10).count { it.reason == "mp_cashout" })
        assertEquals(
            Wallet.STARTER_GRANT - StakeTier.Casual.buyIn + 1_500,
            newWallets().findOrCreate(user).balance,
        )
    }

    @Test
    fun cashOut_nullFinalStack_refundsTheFundedBuyIn() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, StakeTier.Casual) // −1000, balance 9000

        // No live stack known (sat but no hand was ever dealt) → refund the
        // full funded amount.
        val result = service.cashOut(user, finalStack = null)

        assertTrue(result is CashOutResult.CashedOut, "expected CashedOut, was $result")
        assertEquals(StakeTier.Casual.buyIn, result.refunded)
        assertEquals(Wallet.STARTER_GRANT, result.balanceAfter) // net zero
        assertNull(newTableSessions().findActiveForUser(user))
    }

    @Test
    fun cashOut_nullFinalStack_refundsBuyInTimesRebuys() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, StakeTier.Casual) // −1000
        service.rebuy(user)                            // −1000 (balance 8000)

        val result = service.cashOut(user, finalStack = null)

        assertTrue(result is CashOutResult.CashedOut)
        // Funded = buyIn × (1 + rebuyCount) = 1000 × 2 = 2000 → net zero.
        assertEquals(StakeTier.Casual.buyIn * 2, result.refunded)
        assertEquals(Wallet.STARTER_GRANT, result.balanceAfter)
    }

    private fun newService(clock: Clock = Clock.System): DefaultTableSessionService =
        DefaultTableSessionService(
            database = database,
            tableSessions = PostgresTableSessionRepository(database, clock),
            wallets = PostgresWalletRepository(database, clock),
            clock = clock,
        )

    private fun newWallets(clock: Clock = Clock.System) = PostgresWalletRepository(database, clock)
    private fun newTableSessions(clock: Clock = Clock.System) = PostgresTableSessionRepository(database, clock)

    /** Fresh UUID + matching auth.users row so the V11 FK on wallets is satisfied. */
    private fun newUser(): UserId = seedAuthUser()

    private companion object {
        const val ROOM = "ABC234"
    }
}
