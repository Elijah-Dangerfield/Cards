package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.TableSessionsTable
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.CashOutResult
import com.dangerfield.cards.server.domain.RebuyResult
import com.dangerfield.cards.server.domain.SitDownResult
import com.dangerfield.cards.server.domain.TableSessionRepository
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
    fun sitDown_realStakes_debitsBuyIn_andOpensSession() = runTest {
        val service = newService()
        val user = newUser()

        val result = service.sitDown(user, ROOM, CASUAL_BUY_IN)

        assertTrue(result is SitDownResult.Funded, "expected Funded, was $result")
        assertEquals(CASUAL_BUY_IN, result.startingStack)
        assertEquals(Wallet.STARTER_GRANT - CASUAL_BUY_IN, result.balanceAfter)

        val session = newTableSessions().findActiveForUser(user)
        assertEquals(CASUAL_BUY_IN, session?.buyIn)
        assertEquals(TableSessionStatus.Open, session?.status)
        assertEquals(0, session?.rebuyCount)

        val events = newWallets().recentEvents(user, 10)
        assertEquals(listOf("mp_buyin", "starter_grant"), events.map { it.reason })
        assertEquals(-CASUAL_BUY_IN, events.single { it.reason == "mp_buyin" }.delta)
    }

    @Test
    fun sitDown_subFloorBuyIn_chargesTheCoercedStack_neverMintsTheDifference() = runTest {
        val service = newService()
        val user = newUser()

        // A Room whose buy-in slipped below the floor (a future constructor or a
        // matchmaking tier bypassing the HTTP create-route floor). The engine
        // seats every player at RoomSettings.forBuyIn(buyIn).startingStack, which
        // coerces up to MIN_BUY_IN — so the wallet must be debited that same
        // coerced amount. Debiting the raw sub-floor value would seat a 100-chip
        // stack against a 50-chip debit, minting the 50-chip difference.
        val subFloor = RoomSettings.MIN_BUY_IN - 50
        val result = service.sitDown(user, ROOM, requestedBuyIn = subFloor, enforceEntryBar = false)

        assertTrue(result is SitDownResult.Funded, "expected Funded, was $result")
        assertEquals(RoomSettings.MIN_BUY_IN, result.startingStack, "seated + charged at the floor")
        assertEquals(Wallet.STARTER_GRANT - RoomSettings.MIN_BUY_IN, result.balanceAfter)
        assertEquals(RoomSettings.MIN_BUY_IN, newTableSessions().findActiveForUser(user)?.buyIn, "row stores the charged amount")
        assertEquals(
            -RoomSettings.MIN_BUY_IN,
            newWallets().recentEvents(user, 10).single { it.reason == "mp_buyin" }.delta,
            "exactly the floor left the wallet — no minted chips",
        )
    }

    @Test
    fun sitDown_belowEntryBar_whenBuyInExceeds25PercentOfWallet() = runTest {
        val service = newService()
        val user = newUser()

        // Starter grant is 10k; a 5k buy-in needs a 20k wallet to clear the 25% bar.
        val result = service.sitDown(user, ROOM, STANDARD_BUY_IN)

        assertTrue(result is SitDownResult.BelowEntryBar, "expected BelowEntryBar, was $result")
        assertEquals(Wallet.STARTER_GRANT, result.balance)
        assertEquals(STANDARD_BUY_IN * 4, result.minBalance)
        // Rejected before any movement: no session row, no debit — only the
        // lazy-create's starter grant is journaled.
        assertNull(newTableSessions().findActiveForUser(user))
        assertEquals(
            listOf("starter_grant"),
            newWallets().recentEvents(user, 10).map { it.reason },
        )
    }

    @Test
    fun sitDown_entryBarSkipped_fundsEvenABigBuyIn_forPrivateTables() = runTest {
        val service = newService()
        val user = newUser()

        // Same 5k buy-in that fails the bar above, but a private table skips it.
        val result = service.sitDown(user, ROOM, STANDARD_BUY_IN, enforceEntryBar = false)

        assertTrue(result is SitDownResult.Funded, "private tables skip the entry bar, was $result")
        assertEquals(Wallet.STARTER_GRANT - STANDARD_BUY_IN, result.balanceAfter)
    }

    @Test
    fun sitDown_whenAlreadySeated_returnsAlreadyAtTable_andDoesNotDoubleDebit() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, CASUAL_BUY_IN)

        val second = service.sitDown(user, "ZZZZZZ", CASUAL_BUY_IN)

        assertTrue(second is SitDownResult.AlreadyAtTable, "expected AlreadyAtTable, was $second")
        assertEquals(ROOM, second.roomCode)
        // Still exactly one buy-in debit.
        assertEquals(Wallet.STARTER_GRANT - CASUAL_BUY_IN, newWallets().findOrCreate(user).balance)
        assertEquals(1, newWallets().recentEvents(user, 10).count { it.reason == "mp_buyin" })
    }

    @Test
    fun cashOut_creditsFinalStack_andClosesSession() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, CASUAL_BUY_IN)

        val result = service.cashOut(user, finalStack = 1_500)

        assertTrue(result is CashOutResult.CashedOut, "expected CashedOut, was $result")
        assertEquals(1_500, result.refunded)
        // 10000 − 1000 buy-in + 1500 cash-out = 10500.
        assertEquals(Wallet.STARTER_GRANT - CASUAL_BUY_IN + 1_500, result.balanceAfter)
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
        service.sitDown(user, ROOM, CASUAL_BUY_IN)

        val result = service.rebuy(user)

        assertTrue(result is RebuyResult.ReboughtIn, "expected ReboughtIn, was $result")
        assertEquals(CASUAL_BUY_IN, result.startingStack)
        assertEquals(Wallet.STARTER_GRANT - 2 * CASUAL_BUY_IN, result.balanceAfter)
        assertEquals(1, newTableSessions().findActiveForUser(user)?.rebuyCount)
        assertEquals(1, newWallets().recentEvents(user, 10).count { it.reason == "mp_rebuy" })
    }

    @Test
    fun fullSit_ledgerNetsToProfitAndLoss() = runTest {
        val service = newService()
        val user = newUser()

        service.sitDown(user, ROOM, CASUAL_BUY_IN) // −1000
        service.rebuy(user)                            // −1000
        val cashOut = service.cashOut(user, finalStack = 3_000) // +3000

        // Net session P/L = +1000 → 10000 → 11000.
        assertTrue(cashOut is CashOutResult.CashedOut)
        assertEquals(Wallet.STARTER_GRANT + 1_000, cashOut.balanceAfter)
        assertEquals(Wallet.STARTER_GRANT + 1_000, newWallets().findOrCreate(user).balance)

        val reasons = newWallets().recentEvents(user, 10).map { it.reason }.toSet()
        assertEquals(setOf("starter_grant", "mp_buyin", "mp_rebuy", "mp_cashout"), reasons)
    }

    @Test
    fun cashOut_resumingFromClosing_creditsExactlyOnce() = runTest {
        // Simulates a crash mid-cash-out: the status flipped to `closing` but
        // the credit never landed. A boot sweep re-runs cash-out; the keyed,
        // idempotent credit + forward-only status must settle exactly once.
        val service = newService()
        val tableSessions = newTableSessions()
        val user = newUser()
        val funded = service.sitDown(user, ROOM, CASUAL_BUY_IN) as SitDownResult.Funded
        tableSessions.markClosing(funded.sessionId)

        val first = service.cashOut(user, finalStack = 1_500)
        val second = service.cashOut(user, finalStack = 1_500)

        assertTrue(first is CashOutResult.CashedOut)
        assertEquals(Wallet.STARTER_GRANT - CASUAL_BUY_IN + 1_500, first.balanceAfter)
        // Session is closed now → the resumed call is a no-op, no second credit.
        assertEquals(CashOutResult.NoActiveSession, second)
        assertEquals(1, newWallets().recentEvents(user, 10).count { it.reason == "mp_cashout" })
        assertEquals(
            Wallet.STARTER_GRANT - CASUAL_BUY_IN + 1_500,
            newWallets().findOrCreate(user).balance,
        )
    }

    @Test
    fun cashOut_nullFinalStack_refundsTheFundedBuyIn() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, CASUAL_BUY_IN) // −1000, balance 9000

        // No live stack known (sat but no hand was ever dealt) → refund the
        // full funded amount.
        val result = service.cashOut(user, finalStack = null)

        assertTrue(result is CashOutResult.CashedOut, "expected CashedOut, was $result")
        assertEquals(CASUAL_BUY_IN, result.refunded)
        assertEquals(Wallet.STARTER_GRANT, result.balanceAfter) // net zero
        assertNull(newTableSessions().findActiveForUser(user))
    }

    @Test
    fun cashOut_nullFinalStack_refundsBuyInTimesRebuys() = runTest {
        val service = newService()
        val user = newUser()
        service.sitDown(user, ROOM, CASUAL_BUY_IN) // −1000
        service.rebuy(user)                            // −1000 (balance 8000)

        val result = service.cashOut(user, finalStack = null)

        assertTrue(result is CashOutResult.CashedOut)
        // Funded = buyIn × (1 + rebuyCount) = 1000 × 2 = 2000 → net zero.
        assertEquals(CASUAL_BUY_IN * 2, result.refunded)
        assertEquals(Wallet.STARTER_GRANT, result.balanceAfter)
    }

    private fun newService(
        clock: Clock = Clock.System,
        subsidyDailyCap: Long = DefaultTableSessionService.DEFAULT_SUBSIDY_DAILY_CAP,
    ): DefaultTableSessionService =
        DefaultTableSessionService(
            database = database,
            tableSessions = PostgresTableSessionRepository(database, clock),
            wallets = PostgresWalletRepository(database, clock),
            clock = clock,
            subsidyDailyCap = subsidyDailyCap,
        )

    // ---------- disclosed-bot subsidy ----------

    @Test
    fun subsidizedSitDown_skipsEntryBar_andFunds() = runTest {
        val service = newService()
        val user = newUser()

        // A 5k buy-in fails the public entry bar (needs 20k) — but a subsidised
        // bot table skips the bar, so a new player can sit.
        val result = service.sitDown(user, ROOM, STANDARD_BUY_IN, enforceEntryBar = false, subsidized = true)

        assertTrue(result is SitDownResult.Funded, "subsidised sit funds despite the entry bar, was $result")
        assertTrue(newTableSessions().findActiveForUser(user)!!.subsidized, "the session is tagged subsidised")
    }

    @Test
    fun subsidizedCashOut_recordsTheNetHouseFundedWin() = runTest {
        val service = newService()
        val tableSessions = newTableSessions()
        val user = newUser()
        val funded = service.sitDown(user, ROOM, CASUAL_BUY_IN, subsidized = true) as SitDownResult.Funded

        // Win 2,500 over the 1,000 buy-in.
        service.cashOut(user, finalStack = CASUAL_BUY_IN + 2_500)

        assertEquals(2_500, tableSessions.find(funded.sessionId)!!.subsidyGranted, "net win is recorded for the cap")
        // A real win still pays out in full.
        assertEquals(Wallet.STARTER_GRANT + 2_500, newWallets().findOrCreate(user).balance)
    }

    @Test
    fun subsidizedCashOut_aLosingSession_grantsNothing() = runTest {
        val service = newService()
        val tableSessions = newTableSessions()
        val user = newUser()
        val funded = service.sitDown(user, ROOM, CASUAL_BUY_IN, subsidized = true) as SitDownResult.Funded

        service.cashOut(user, finalStack = 200) // walked away down

        assertEquals(0, tableSessions.find(funded.sessionId)!!.subsidyGranted, "a loss is no house subsidy")
    }

    @Test
    fun subsidizedCashOut_resumedAfterCrash_recordsTheWinExactlyOnce() = runTest {
        // Crash window: the cash-out credited the wallet but the process died
        // before `markClosed`, so the boot sweep re-runs cash-out over a still-
        // `closing` session. The wallet credit is keyed (idempotent), but the
        // subsidy record + `bot_subsidy_payout` telemetry must NOT fire a second
        // time — else the Grafana budget dashboard double-counts the same win.
        val recordingRepo = RecordCountingTableSessions(
            delegate = PostgresTableSessionRepository(database, Clock.System),
            swallowFirstMarkClosed = true, // simulate the crash before the first close
        )
        val service = DefaultTableSessionService(
            database = database,
            tableSessions = recordingRepo,
            wallets = newWallets(),
            clock = Clock.System,
        )
        val user = newUser()
        service.sitDown(user, ROOM, CASUAL_BUY_IN, subsidized = true)

        // First cash-out credits the win but "crashes" before markClosed → the
        // session stays active (`closing`), so the sweep can re-run it.
        service.cashOut(user, finalStack = CASUAL_BUY_IN + 2_500)
        // Boot-sweep resume: same keyed credit (already applied), close for real.
        service.cashOut(user, finalStack = CASUAL_BUY_IN + 2_500)

        assertEquals(
            1,
            recordingRepo.recordSubsidyCalls,
            "the subsidy win is recorded once, not re-counted on the crash-resumed cash-out",
        )
        // The player still keeps every chip; only the bookkeeping/telemetry is guarded.
        assertEquals(Wallet.STARTER_GRANT + 2_500, newWallets().findOrCreate(user).balance)
    }

    /**
     * Wraps the real repo to (a) count `recordSubsidyGranted` calls and (b)
     * optionally swallow the first `markClosed` so a cash-out leaves the session
     * `closing` — reproducing the crash-before-close window the boot sweep resumes.
     */
    private class RecordCountingTableSessions(
        private val delegate: TableSessionRepository,
        private val swallowFirstMarkClosed: Boolean,
    ) : TableSessionRepository by delegate {
        var recordSubsidyCalls = 0
            private set
        private var markClosedCalls = 0

        override suspend fun recordSubsidyGranted(sessionId: java.util.UUID, amount: Long) {
            recordSubsidyCalls++
            delegate.recordSubsidyGranted(sessionId, amount)
        }

        override suspend fun markClosed(sessionId: java.util.UUID): Boolean {
            if (swallowFirstMarkClosed && markClosedCalls++ == 0) return false
            return delegate.markClosed(sessionId)
        }
    }

    @Test
    fun subsidizedWin_overTheDailyCap_paysOutInFull_nothingClawedBack() = runTest {
        // The cap is 2,000 but the player wins 10,000 against the bots (some of it
        // possibly after a human joined). We never rob them: the cash-out credits
        // every chip. The cap only gates STARTING the next bot table, never the
        // payout — so "I don't want to rob people of their money" holds.
        val service = newService(subsidyDailyCap = 2_000)
        val tableSessions = newTableSessions()
        val user = newUser()
        val funded = service.sitDown(user, ROOM, CASUAL_BUY_IN, subsidized = true) as SitDownResult.Funded

        val cashed = service.cashOut(user, finalStack = CASUAL_BUY_IN + 10_000)

        assertTrue(cashed is CashOutResult.CashedOut)
        assertEquals(CASUAL_BUY_IN + 10_000, cashed.refunded, "the whole stack is paid out, cap or not")
        assertEquals(Wallet.STARTER_GRANT + 10_000, newWallets().findOrCreate(user).balance, "wallet keeps every chip")
        assertEquals(10_000, tableSessions.find(funded.sessionId)!!.subsidyGranted, "the full win counts toward the cap")
    }

    @Test
    fun subsidyCap_blocksANewBotTable_afterTheDailyLimit_butNotRealTables() = runTest {
        val service = newService(subsidyDailyCap = 2_000)
        val user = newUser()

        // Win 2,500 on a subsidised table → over the 2,000 cap.
        service.sitDown(user, ROOM, CASUAL_BUY_IN, subsidized = true)
        service.cashOut(user, finalStack = CASUAL_BUY_IN + 2_500)

        // A new SUBSIDISED bot table is refused…
        val capped = service.sitDown(user, "ROOM2", CASUAL_BUY_IN, subsidized = true)
        assertTrue(capped is SitDownResult.SubsidyCapReached, "over-cap → no new bot table, was $capped")
        assertEquals(2_500, capped.grantedToday)

        // …but a normal (non-subsidised) table still funds — only the subsidy is gated.
        val realTable = service.sitDown(user, "ROOM3", CASUAL_BUY_IN, subsidized = false)
        assertTrue(realTable is SitDownResult.Funded, "the cap gates only bot tables, was $realTable")
    }

    @Test
    fun incrementRebuy_returnsMonotonicPostIncrementCounts_andPersists() = runTest {
        // The rebuy ledger key (`table:{id}:rebuy:{n}`) needs a distinct, gap-free
        // n per top-up. The atomic UPDATE computes `rebuy_count + 1` server-side
        // off the live row — no read-then-write window — and hands back the new
        // count. (True concurrent contention can't be reproduced at this layer:
        // the suspended-transaction harness serializes, and production serializes
        // rebuys through the game mutex regardless; this pins the value contract.)
        val repo = newTableSessions()
        val user = newUser()
        val funded = newService().sitDown(user, ROOM, CASUAL_BUY_IN) as SitDownResult.Funded

        assertEquals(listOf(1, 2, 3), (1..3).map { repo.incrementRebuy(funded.sessionId) })
        assertEquals(3, repo.find(funded.sessionId)!!.rebuyCount, "the last write persists")
    }

    private fun newWallets(clock: Clock = Clock.System) = PostgresWalletRepository(database, clock)
    private fun newTableSessions(clock: Clock = Clock.System) = PostgresTableSessionRepository(database, clock)

    /** Fresh UUID + matching auth.users row so the V11 FK on wallets is satisfied. */
    private fun newUser(): UserId = seedAuthUser()

    private companion object {
        const val ROOM = "ABC234"
        // Buy-in amounts (develop's free-form Room.buyIn): a 1k clears the entry
        // bar against the 10k starter grant, a 5k doesn't (needs 20k).
        const val CASUAL_BUY_IN = 1_000L
        const val STANDARD_BUY_IN = 5_000L
    }
}
