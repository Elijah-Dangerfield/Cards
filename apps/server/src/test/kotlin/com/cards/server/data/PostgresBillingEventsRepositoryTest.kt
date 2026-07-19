package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.BillingEventsTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.BillingEventAction
import com.dangerfield.cards.server.domain.BillingEventAttempt
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the billing-events disposition log. Hits real Postgres
 * so the `(store, transaction_id)` upsert — one evolving row per transaction
 * that bumps `attempt_count` and rewrites the latest disposition — is genuinely
 * exercised.
 */
@OptIn(ExperimentalTime::class)
class PostgresBillingEventsRepositoryTest : DatabaseTest() {

    private val caller = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val owner = UserId(UUID.fromString("52f3f9c1-1a94-4640-b24c-560a9b7534eb"))

    @After
    fun cleanTables() {
        database.blockingTransaction { BillingEventsTable.deleteAll() }
    }

    @Test
    fun record_firstAttempt_insertsWithCountOne() = runTest {
        val repo = PostgresBillingEventsRepository(database, Clock.System)

        repo.record(attempt(action = BillingEventAction.ClaimSignIn, reason = "account_mismatch_anonymous"))

        val row = singleRow()
        assertEquals(1, row.attemptCount)
        assertEquals("claim_sign_in", row.finalAction)
        assertEquals(owner.value, row.receiptOwner)
    }

    @Test
    fun record_repeatAttempt_bumpsCount_andRewritesLatestDisposition() = runTest {
        val repo = PostgresBillingEventsRepository(database, Clock.System)

        // A purchase seen three times: pending-ish mismatch, mismatch, then the
        // relaxed grant lands. One row tells the whole journey.
        repo.record(attempt(action = BillingEventAction.ClaimSignIn, reason = "account_mismatch_anonymous"))
        repo.record(attempt(action = BillingEventAction.Mismatch, reason = "account_mismatch_rate_limited"))
        repo.record(attempt(action = BillingEventAction.GrantedOnReplay, reason = "account_mismatch_relaxed"))

        val row = singleRow()
        assertEquals(3, row.attemptCount, "each attempt bumps the count")
        assertEquals("granted_on_replay", row.finalAction, "the latest disposition wins")
        assertEquals(1L, rowCount(), "still exactly one row for the transaction")
    }

    @Test
    fun record_distinctTransactions_areSeparateRows() = runTest {
        val repo = PostgresBillingEventsRepository(database, Clock.System)

        repo.record(attempt(transactionId = "txn-a", action = BillingEventAction.Granted))
        repo.record(attempt(transactionId = "txn-b", action = BillingEventAction.Granted))

        assertEquals(2L, rowCount())
    }

    private data class Row(val attemptCount: Int, val finalAction: String, val receiptOwner: UUID?)

    private fun singleRow(): Row = database.blockingTransaction {
        BillingEventsTable.selectAll().single().let {
            Row(
                attemptCount = it[BillingEventsTable.attemptCount],
                finalAction = it[BillingEventsTable.finalAction],
                receiptOwner = it[BillingEventsTable.receiptOwner],
            )
        }
    }

    private fun rowCount(): Long = database.blockingTransaction {
        BillingEventsTable.selectAll().count()
    }

    private fun attempt(
        transactionId: String = "txn-1",
        action: BillingEventAction,
        reason: String? = null,
    ) = BillingEventAttempt(
        store = "apple",
        transactionId = transactionId,
        callerUser = caller,
        receiptOwner = owner,
        productId = "chip_pack_medium",
        reason = reason,
        action = action,
    )
}
