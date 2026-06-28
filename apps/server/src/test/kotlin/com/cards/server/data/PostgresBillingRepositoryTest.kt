package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.BillingTransactionsTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.RedeemResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the Postgres-backed billing repo. Hits real
 * Postgres via testcontainers so the `(store, order_id)` unique constraint
 * — the idempotency boundary that stops a forged-or-retried receipt from
 * minting chips twice — is genuinely exercised, not faked.
 */
@OptIn(ExperimentalTime::class)
class PostgresBillingRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            BillingTransactionsTable.deleteAll()
            WalletEventsTable.deleteAll()
            WalletsTable.deleteAll()
        }
    }

    @Test
    fun redeem_firstTime_grantsChips_overStarter() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val result = repo.redeem(
            userId = userId,
            store = "apple",
            orderId = "txn-1",
            productId = "chip_pack_medium",
            grantedChips = 30_000,
        )

        assertIs<RedeemResult.Granted>(result)
        assertEquals(Wallet.STARTER_GRANT + 30_000, result.balance)
        assertEquals(1, billingRowCount())
    }

    @Test
    fun redeem_sameStoreOrder_isIdempotent_grantsOnce() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val first = repo.redeem(userId, "apple", "txn-1", "chip_pack_medium", 30_000)
        val second = repo.redeem(userId, "apple", "txn-1", "chip_pack_medium", 30_000)

        assertIs<RedeemResult.Granted>(first)
        assertIs<RedeemResult.AlreadyRedeemed>(second)
        // Balance reflects exactly one grant, and only one audit row exists.
        assertEquals(Wallet.STARTER_GRANT + 30_000, second.balance)
        assertEquals(1, billingRowCount())
    }

    @Test
    fun redeem_sameOrderId_differentStore_bothGrant() = runTest {
        // The unique key is (store, order_id), so a collision only happens
        // within one store — Apple txn "x" and Google txn "x" are distinct.
        val repo = newRepo()
        val userId = newUser()

        repo.redeem(userId, "apple", "shared-id", "chip_pack_medium", 30_000)
        val google = repo.redeem(userId, "google", "shared-id", "chip_pack_medium", 30_000)

        assertIs<RedeemResult.Granted>(google)
        assertEquals(Wallet.STARTER_GRANT + 60_000, google.balance)
        assertEquals(2, billingRowCount())
    }

    @Test
    fun redeem_distinctTransactions_stack() = runTest {
        val repo = newRepo()
        val userId = newUser()

        repo.redeem(userId, "apple", "txn-1", "chip_pack_small", 5_000)
        val second = repo.redeem(userId, "apple", "txn-2", "chip_pack_medium", 30_000)

        assertEquals(Wallet.STARTER_GRANT + 35_000, second.balance)
        assertEquals(2, billingRowCount())
    }

    private fun billingRowCount(): Long = database.blockingTransaction {
        BillingTransactionsTable.selectAll().count()
    }

    private fun newRepo(clock: Clock = Clock.System): PostgresBillingRepository =
        PostgresBillingRepository(database = database, clock = clock)

    private fun newUser(): UserId = seedAuthUser()
}
