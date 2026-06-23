package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.gameplay.StakeTier
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.TableSessionsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.CashOutResult
import com.dangerfield.cards.server.domain.RebuyResult
import com.dangerfield.cards.server.domain.SitDownResult
import com.dangerfield.cards.server.domain.TableSessionRepository
import com.dangerfield.cards.server.domain.TableSessionService
import com.dangerfield.cards.server.domain.TableSessionStatus
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.WalletRepository
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Default [TableSessionService] — wires the `table_sessions` lifecycle to
 * the chip wallet.
 *
 * Buy-in is the one operation that must be atomic: the row insert (which
 * claims the user's single-active-session slot via the partial unique
 * index) and the wallet debit run in a single `database.transaction`, so
 * a crash can never leave a funded row without a matching debit — which
 * cash-out would otherwise credit as duplicated chips. On either failure
 * (slot taken, or balance dropped under the buy-in since the entry-bar
 * read) the whole transaction rolls back.
 *
 * Rebuy and cash-out don't need a single transaction: they're crash-safe
 * because the wallet movement is keyed (idempotent replay) and the
 * `table_sessions` status flips are forward-only. A boot sweep can re-run
 * cash-out over a `closing` row and land on identical wallet state.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class DefaultTableSessionService(
    private val database: Database,
    private val tableSessions: TableSessionRepository,
    private val wallets: WalletRepository,
    private val clock: Clock,
) : TableSessionService {

    override suspend fun sitDown(userId: UserId, roomCode: String, tier: StakeTier): SitDownResult {
        val buyIn = tier.buyIn

        // Practice is free-play, like bots: the seat gets practice chips, the
        // wallet never moves, and there's no durable session to reconcile.
        if (tier == StakeTier.Practice) {
            return SitDownResult.FreePlay(startingStack = buyIn)
        }

        // Clean result for the common "already seated" / double-tap case; the
        // partial unique index below is the actual guarantee.
        tableSessions.findActiveForUser(userId)?.let {
            return SitDownResult.AlreadyAtTable(roomCode = it.roomCode)
        }

        // Anti-smurf entry bar: the buy-in must be ≤ 25% of the wallet, i.e.
        // the wallet must cover at least four buy-ins at this tier.
        val balance = wallets.findOrCreate(userId).balance
        val minBalance = buyIn * MIN_BALANCE_BUYIN_MULTIPLE
        if (balance < minBalance) {
            return SitDownResult.BelowEntryBar(balance = balance, minBalance = minBalance)
        }

        val sessionId = UUID.randomUUID()
        val now = clock.now()
        return try {
            database.transaction {
                try {
                    TableSessionsTable.insert {
                        it[TableSessionsTable.sessionId] = sessionId
                        it[TableSessionsTable.userId] = userId.value
                        it[TableSessionsTable.roomCode] = roomCode
                        it[TableSessionsTable.stakeTier] = tier.name
                        it[TableSessionsTable.buyIn] = buyIn
                        it[TableSessionsTable.rebuyCount] = 0
                        it[TableSessionsTable.status] = TableSessionStatus.Open.dbValue
                        it[TableSessionsTable.openedAt] = now.toJavaInstant()
                    }
                } catch (e: ExposedSQLException) {
                    // PK collision (fresh UUID — won't happen) or the partial
                    // unique index (this user already has an active session).
                    if (e.isUniqueViolation()) throw AlreadyActiveSignal else throw e
                }
                when (
                    val outcome = WalletLedger.applyInCurrentTransaction(
                        userId = userId,
                        idempotencyKey = buyInKey(sessionId),
                        delta = -buyIn,
                        reason = REASON_BUYIN,
                        now = now,
                    )
                ) {
                    // Roll the whole transaction (incl. the row insert) back so
                    // there's never a funded row without its debit.
                    is ApplyOutcome.InsufficientChips -> throw InsufficientSignal(outcome.balance)
                    is ApplyOutcome.Applied -> SitDownResult.Funded(
                        sessionId = sessionId,
                        startingStack = buyIn,
                        balanceAfter = outcome.balance,
                    )
                }
            }
        } catch (e: AlreadyActiveSignal) {
            SitDownResult.AlreadyAtTable(
                roomCode = tableSessions.findActiveForUser(userId)?.roomCode ?: roomCode,
            )
        } catch (e: InsufficientSignal) {
            SitDownResult.InsufficientChips(balance = e.balance)
        }
    }

    override suspend fun rebuy(userId: UserId): RebuyResult {
        val session = tableSessions.findActiveForUser(userId) ?: return RebuyResult.NoActiveSession
        val buyIn = session.buyIn

        // Re-apply the entry bar so a rebuy can't dump the bankroll into one
        // tier — same 25% rule as sit-down.
        val balance = wallets.findOrCreate(userId).balance
        val minBalance = buyIn * MIN_BALANCE_BUYIN_MULTIPLE
        if (balance < minBalance) {
            return RebuyResult.BelowEntryBar(balance = balance, minBalance = minBalance)
        }

        // The game session's mutex serializes rebuys per table, so the
        // increment → debit pair can't interleave; the rebuy index makes the
        // ledger key unique per top-up.
        val n = tableSessions.incrementRebuy(session.sessionId)
        return when (
            val outcome = wallets.apply(
                userId = userId,
                idempotencyKey = rebuyKey(session.sessionId, n),
                delta = -buyIn,
                reason = REASON_REBUY,
            )
        ) {
            is ApplyOutcome.InsufficientChips -> RebuyResult.InsufficientChips(balance = outcome.balance)
            is ApplyOutcome.Applied -> RebuyResult.ReboughtIn(
                startingStack = buyIn,
                balanceAfter = outcome.balance,
            )
        }
    }

    override suspend fun cashOut(userId: UserId, finalStack: Long?): CashOutResult {
        val session = tableSessions.findActiveForUser(userId) ?: return CashOutResult.NoActiveSession

        // No live stack known (sat but no hand dealt, or crash recovery with
        // no snapshot seat) → refund everything that was funded.
        val refund = (finalStack ?: session.buyIn * (1 + session.rebuyCount)).coerceAtLeast(0L)

        // Forward-only interlock: flip open → closing before crediting, so a
        // crash mid-cash-out is recoverable — a sweep re-runs the keyed,
        // idempotent credit and re-flips to closed. markClosing is a no-op
        // when resuming an already-closing row.
        tableSessions.markClosing(session.sessionId)
        val outcome = wallets.apply(
            userId = userId,
            idempotencyKey = cashOutKey(session.sessionId),
            delta = refund,
            reason = REASON_CASHOUT,
        )
        tableSessions.markClosed(session.sessionId)
        return CashOutResult.CashedOut(refunded = refund, balanceAfter = outcome.balance)
    }

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    /** Internal control-flow signal: roll the buy-in transaction back, the slot was taken. */
    private object AlreadyActiveSignal : RuntimeException() {
        private fun readResolve(): Any = AlreadyActiveSignal
    }

    /** Internal control-flow signal: roll the buy-in transaction back, balance fell short. */
    private class InsufficientSignal(val balance: Long) : RuntimeException()

    companion object {
        /** Wallet must cover ≥ this many buy-ins to enter (or rebuy at) a tier — the 25% rule. */
        const val MIN_BALANCE_BUYIN_MULTIPLE = 4L

        const val REASON_BUYIN = "mp_buyin"
        const val REASON_REBUY = "mp_rebuy"
        const val REASON_CASHOUT = "mp_cashout"

        fun buyInKey(sessionId: UUID): String = "table:$sessionId:buyin"
        fun rebuyKey(sessionId: UUID, n: Int): String = "table:$sessionId:rebuy:$n"
        fun cashOutKey(sessionId: UUID): String = "table:$sessionId:cashout"

        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
