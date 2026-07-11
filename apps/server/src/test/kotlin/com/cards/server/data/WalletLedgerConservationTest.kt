package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.BillingTransactionsTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.PurchaseEnvironment
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ECON-1 — the conservation invariant: every chip in every balance is
 * explainable by the ledger, i.e. `SUM(wallets.balance) ==
 * SUM(wallet_events.delta)` at all times. Prod drifted 20,000 chips
 * because the starter grant set the wallet balance without writing a
 * ledger row; this suite pins that no mutation path can reintroduce a
 * silent (un-ledgered) balance change.
 *
 * The same query backs the "Ledger conservation drift" stat on the
 * `cards-economy` dashboard — keep the two in sync.
 */
@OptIn(ExperimentalTime::class)
class WalletLedgerConservationTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            BillingTransactionsTable.deleteAll()
            WalletEventsTable.deleteAll()
            WalletsTable.deleteAll()
        }
    }

    @Test
    fun starterGrant_isLedgered() = runTest {
        // The prod incident path: a fresh wallet's 10,000 chips existed with
        // no ledger row to explain them.
        wallets().findOrCreate(seedAuthUser())

        assertConservation()
        assertEquals(
            listOf("starter_grant"),
            reasons(),
            "the starter grant must be explainable by the ledger",
        )
    }

    @Test
    fun starterGrant_viaLazyCreateOnFirstApply_isLedgered() = runTest {
        val userId = seedAuthUser()

        wallets().apply(userId, "mp.buyin.h1", -2_000, "mp_buyin")

        assertConservation()
    }

    @Test
    fun everyMutationPath_preservesConservation() = runTest {
        val repo = wallets()
        val billing = PostgresBillingRepository(database = database, clock = Clock.System)
        val a = seedAuthUser()
        val b = seedAuthUser()

        repo.findOrCreate(a)
        billing.redeem(b, "apple", "txn-1", "chip_pack_medium", 30_000, PurchaseEnvironment.Production)
        billing.redeem(b, "apple", "txn-1", "chip_pack_medium", 30_000, PurchaseEnvironment.Production)
        billing.redeem(a, "apple", "txn-2", "chip_pack_small", 5_000, PurchaseEnvironment.Sandbox)
        repo.apply(a, "mp.buyin.h1", -5_000, "mp_buyin")
        repo.apply(a, "mp.buyin.h1", -5_000, "mp_buyin")
        repo.apply(a, "mp.cashout.h1", 7_500, "mp_cashout")
        repo.apply(b, "shop.order-1", -1_000, "shop.card_back")
        repo.apply(a, "overdraft", -1_000_000, "mp_buyin")

        assertConservation()
    }

    private fun wallets() = PostgresWalletRepository(database = database, clock = Clock.System)

    private fun assertConservation() {
        val (balances, deltas) = database.blockingTransaction {
            val balances = WalletsTable.selectAll().sumOf { it[WalletsTable.balance] }
            val deltas = WalletEventsTable.selectAll().sumOf { it[WalletEventsTable.delta] }
            balances to deltas
        }
        assertEquals(
            balances,
            deltas,
            "conservation broken: balances hold $balances chips but the ledger explains $deltas",
        )
    }

    private fun reasons(): List<String> = database.blockingTransaction {
        WalletEventsTable.selectAll().map { it[WalletEventsTable.reason] }
    }
}
