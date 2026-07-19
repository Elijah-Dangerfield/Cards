package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.BillingTransactionsTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.BillingRepository
import com.dangerfield.cards.server.domain.GrantKind
import com.dangerfield.cards.server.domain.PurchaseEnvironment
import com.dangerfield.cards.server.domain.RedeemResult
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [BillingRepository]. The grant + the audit row commit in
 * one `database.transaction`, so a crash can never leave a credited wallet
 * with no `billing_transactions` record (which a retry would then credit
 * again) or vice-versa.
 *
 * Idempotency is anchored on the `(store, order_id)` unique constraint:
 * the insert is attempted first, and a unique violation means the
 * transaction was already redeemed — we short-circuit to the current
 * balance without touching the wallet. The wallet grant itself rides the
 * ledger's own `(user_id, idempotency_key)` dedup with a billing-scoped
 * key, so even a concurrent writer that slips past the row insert can't
 * double-credit.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresBillingRepository(
    private val database: Database,
    private val clock: Clock,
) : BillingRepository {

    override suspend fun redeem(
        userId: UserId,
        store: String,
        orderId: String,
        productId: String,
        grantedChips: Long,
        environment: PurchaseEnvironment,
        kind: GrantKind,
    ): RedeemResult = database.transaction {
        val now = clock.now()

        // Pre-check before inserting: a failed INSERT (the unique-constraint
        // trip on a replay) aborts the whole Postgres transaction, so we
        // can't catch it and keep reading in the same transaction. The
        // common replay path is a read; the unique constraint remains the
        // backstop for the rare concurrent-redeem race, where the loser's
        // whole transaction rolls back and the client's retry then reads the
        // committed row as AlreadyRedeemed.
        if (transactionExists(store, orderId)) {
            return@transaction RedeemResult.AlreadyRedeemed(balance = currentBalance(userId, now))
        }

        BillingTransactionsTable.insert {
            it[BillingTransactionsTable.store] = store
            it[BillingTransactionsTable.orderId] = orderId
            it[BillingTransactionsTable.userId] = userId.value
            it[BillingTransactionsTable.productId] = productId
            it[BillingTransactionsTable.grantedChips] = grantedChips
            it[BillingTransactionsTable.environment] = environment.wire
            it[BillingTransactionsTable.redeemedAt] = now.toJavaInstant()
        }

        val outcome = WalletLedger.applyInCurrentTransaction(
            userId = userId,
            idempotencyKey = "billing.$store.$orderId",
            delta = grantedChips,
            // `iap.` strictly means real money — orphan-sweep guards and the
            // economy dashboards key on the prefix. Sandbox mints (TestFlight,
            // Play license testers) get their own prefix so they can never
            // read as revenue or paying-customer signal. A recovery grant adds a
            // `.replay` / `.goodwill` suffix so it's queryable, but KEEPS the
            // `iap.`/`iap_sandbox.` prefix — a recovered purchase is still real
            // money the user paid, so it must count as spend and protect the
            // account from the orphan sweep (which keys on `reason LIKE 'iap.%'`).
            reason = walletReason(environment, productId, kind),
            now = now,
        )
        when (outcome) {
            is ApplyOutcome.Applied -> RedeemResult.Granted(balance = outcome.balance)
            // A chip-pack grant is always positive, so the wallet's
            // non-negative CHECK can't reject it; treat the impossible
            // branch as granted-at-current-balance rather than inventing a
            // failure shape the caller can't act on.
            is ApplyOutcome.InsufficientChips -> RedeemResult.Granted(balance = outcome.balance)
        }
    }

    private fun walletReason(
        environment: PurchaseEnvironment,
        productId: String,
        kind: GrantKind,
    ): String {
        val prefix = when (environment) {
            PurchaseEnvironment.Production -> "iap"
            PurchaseEnvironment.Sandbox -> "iap_sandbox"
        }
        return "$prefix.$productId${kind.reasonSuffix}"
    }

    private fun transactionExists(store: String, orderId: String): Boolean =
        BillingTransactionsTable
            .selectAll()
            .where {
                (BillingTransactionsTable.store eq store) and
                    (BillingTransactionsTable.orderId eq orderId)
            }
            .limit(1)
            .any()

    private fun currentBalance(userId: UserId, now: kotlin.time.Instant): Long =
        WalletLedger.readWallet(userId)?.balance
            ?: WalletLedger.createWithStarter(userId, now).balance
}
