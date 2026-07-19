package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.BillingEventsTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.BillingEventAttempt
import com.dangerfield.cards.server.domain.BillingEventsRepository
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [BillingEventsRepository]. Upserts one row per
 * `(store, transaction_id)`: an existing row bumps its `attempt_count` and
 * rewrites the latest caller / reason / action; a new transaction inserts with
 * `attempt_count = 1`. Read-then-write is safe because the server is
 * single-writer; the unique constraint backstops a rare concurrent retry.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresBillingEventsRepository(
    private val database: Database,
    private val clock: Clock,
) : BillingEventsRepository {

    override suspend fun attemptCountFor(store: String, transactionId: String): Int =
        database.transaction {
            BillingEventsTable
                .selectAll()
                .where {
                    (BillingEventsTable.store eq store) and
                        (BillingEventsTable.transactionId eq transactionId)
                }
                .limit(1)
                .singleOrNull()
                ?.get(BillingEventsTable.attemptCount)
                ?: 0
        }

    override suspend fun record(event: BillingEventAttempt) {
        database.transaction {
            val now = clock.now().toJavaInstant()
            val existing = BillingEventsTable
                .selectAll()
                .where {
                    (BillingEventsTable.store eq event.store) and
                        (BillingEventsTable.transactionId eq event.transactionId)
                }
                .limit(1)
                .singleOrNull()

            if (existing == null) {
                BillingEventsTable.insert {
                    it[store] = event.store
                    it[transactionId] = event.transactionId
                    it[callerUserId] = event.callerUser.value
                    it[receiptOwner] = event.receiptOwner?.value
                    it[productId] = event.productId
                    it[reason] = event.reason
                    it[finalAction] = event.action.wire
                    it[attemptCount] = 1
                    it[firstSeenAt] = now
                    it[updatedAt] = now
                }
            } else {
                val nextCount = existing[BillingEventsTable.attemptCount] + 1
                BillingEventsTable.update({
                    (BillingEventsTable.store eq event.store) and
                        (BillingEventsTable.transactionId eq event.transactionId)
                }) {
                    it[callerUserId] = event.callerUser.value
                    it[receiptOwner] = event.receiptOwner?.value
                    it[productId] = event.productId
                    it[reason] = event.reason
                    it[finalAction] = event.action.wire
                    it[attemptCount] = nextCount
                    it[updatedAt] = now
                }
            }
        }
    }
}
