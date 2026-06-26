package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.gameplay.RoomSettings
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
import com.dangerfield.cards.server.plugins.SpanAttrs
import com.dangerfield.cards.server.plugins.withSpan
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
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
    // Disclosed-bot subsidy budget: the house funds at most this many chips of
    // net winnings per player per rolling window. Defaulted (tunable later via
    // config) — a launch promotion knob, not a load-bearing invariant.
    private val subsidyDailyCap: Long = DEFAULT_SUBSIDY_DAILY_CAP,
    private val subsidyWindow: kotlin.time.Duration = DEFAULT_SUBSIDY_WINDOW,
) : TableSessionService {

    private val log = org.slf4j.LoggerFactory.getLogger(DefaultTableSessionService::class.java)

    override suspend fun sitDown(
        userId: UserId,
        roomCode: String,
        requestedBuyIn: Long,
        enforceEntryBar: Boolean,
        subsidized: Boolean,
    ): SitDownResult {
        // Charge exactly what the engine seats. A player's table stack is always
        // `RoomSettings.forBuyIn(buyIn).startingStack`, which coerces into
        // [MIN_BUY_IN]..[MAX_BUY_IN] — so a sub-floor (or absurd) room buy-in that
        // ever slips past the HTTP create floor would otherwise seat a coerced
        // stack against a raw debit and mint (or burn) the difference. Coercing
        // here, at the money chokepoint, keeps wallet-debit == seated-stack no
        // matter how the Room was constructed. This coerced value is the session's
        // authoritative buy-in (stored, rebought, and refunded against).
        val buyIn = requestedBuyIn.coerceIn(RoomSettings.MIN_BUY_IN, RoomSettings.MAX_BUY_IN)

        // Clean result for the common "already seated" / double-tap case; the
        // partial unique index below is the actual guarantee.
        tableSessions.findActiveForUser(userId)?.let {
            return SitDownResult.AlreadyAtTable(roomCode = it.roomCode)
        }

        // Disclosed-bot subsidy cap: once today's house-funded winnings hit the
        // budget, no new bot-payout table. Checked off CLOSED sessions, so an
        // in-flight session never blocks itself; the win you're sitting on is
        // never clawed back, only the *next* bot table is gated.
        //
        // Known + accepted (decided 2026-06-23): because the cap is checked at
        // sit-down, it bounds how many bot SESSIONS you start, not a single
        // session's payout — which scales with the buy-in tier. A wealthy player
        // could net more than the daily cap against bots in one high-stakes
        // session before the next table is gated. Left as-is rather than tier-
        // capping the subsidy: chips never cash out (no real-money harm), the
        // buy-in must be affordable to even sit (a fresh account can't reach the
        // high tiers), and the worst case is in-game chip inflation, not loss.
        // Revisit (cap the subsidised buy-in tier) if bot-farming distorts the
        // economy once there's real traffic.
        if (subsidized) {
            val grantedToday = tableSessions.subsidyGrantedSince(userId, clock.now() - subsidyWindow)
            if (grantedToday >= subsidyDailyCap) {
                return SitDownResult.SubsidyCapReached(grantedToday = grantedToday, cap = subsidyDailyCap)
            }
        }

        // Anti-bankroll-dump entry bar (public human tables only): the buy-in must
        // be ≤ 25% of the wallet, i.e. the wallet covers at least four buy-ins.
        // Private friend games + subsidised bot tables skip it — the daily cap is
        // the bot-table guard, and a new player should be able to afford the bots.
        val balance = wallets.findOrCreate(userId).balance
        val minBalance = buyIn * MIN_BALANCE_BUYIN_MULTIPLE
        if (enforceEntryBar && balance < minBalance) {
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
                        it[TableSessionsTable.stakeTier] = stakeLabel(buyIn)
                        it[TableSessionsTable.buyIn] = buyIn
                        it[TableSessionsTable.rebuyCount] = 0
                        it[TableSessionsTable.status] = TableSessionStatus.Open.dbValue
                        it[TableSessionsTable.openedAt] = now.toJavaInstant()
                        it[TableSessionsTable.subsidized] = subsidized
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

    override suspend fun rebuy(userId: UserId, enforceEntryBar: Boolean): RebuyResult {
        val session = tableSessions.findActiveForUser(userId) ?: return RebuyResult.NoActiveSession
        val buyIn = session.buyIn

        // Re-apply the entry bar (public tables only) so a rebuy can't dump the
        // bankroll into one table — same 25% rule as sit-down.
        val balance = wallets.findOrCreate(userId).balance
        val minBalance = buyIn * MIN_BALANCE_BUYIN_MULTIPLE
        if (enforceEntryBar && balance < minBalance) {
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

        // Disclosed-bot subsidy: record the net house-funded win (final stack over
        // everything the player put in) so it counts against their daily cap. Only
        // run this when the credit actually landed *this* call — a crash-resumed
        // cash-out re-applies the keyed credit as a no-op (`wasAlreadyApplied`), and
        // re-recording (harmless, it's a `set`) but re-emitting `bot_subsidy_payout`
        // would double-count the win on the Grafana budget dashboard. Only a
        // positive net is house-funded; a losing session granted nothing.
        val creditJustApplied = (outcome as? ApplyOutcome.Applied)?.wasAlreadyApplied == false
        if (session.subsidized && creditJustApplied) {
            val funded = session.buyIn * (1 + session.rebuyCount)
            val net = (refund - funded).coerceAtLeast(0L)
            if (net > 0) {
                tableSessions.recordSubsidyGranted(session.sessionId, net)
                // Payout telemetry — amount + the user's running draw-down, so the
                // dashboard can watch the budget and flag farming anomalies. Emitted
                // as both a span (Tempo, sliceable) and a log line (Loki).
                val grantedAfter = tableSessions.subsidyGrantedSince(userId, clock.now() - subsidyWindow) + net
                withSpan(
                    name = "bot_subsidy_payout",
                    configure = {
                        setAttribute(SpanAttrs.UserId, userId.value.toString())
                        setAttribute(SpanAttrs.SessionId, session.sessionId.toString())
                        setAttribute(SpanAttrs.BotSubsidyAmount, net)
                        setAttribute(SpanAttrs.BotSubsidyGrantedWindow, grantedAfter)
                        setAttribute(SpanAttrs.BotSubsidyCap, subsidyDailyCap)
                    },
                ) {}
                log.info(
                    "bot_subsidy_payout user={} session={} amount={} grantedWindow={} cap={}",
                    userId.value, session.sessionId, net, grantedAfter, subsidyDailyCap,
                )
            }
        }

        tableSessions.markClosed(session.sessionId)
        return CashOutResult.CashedOut(refunded = refund, balanceAfter = outcome.balance)
    }

    override suspend fun activeUsersInRoom(roomCode: String): List<UserId> =
        tableSessions.findActiveByRoom(roomCode).map { it.userId }

    override suspend fun subsidyBudget(userId: UserId): com.dangerfield.cards.server.domain.SubsidyBudget {
        val grantedToday = tableSessions.subsidyGrantedSince(userId, clock.now() - subsidyWindow)
        return com.dangerfield.cards.server.domain.SubsidyBudget(
            grantedToday = grantedToday,
            cap = subsidyDailyCap,
            remaining = (subsidyDailyCap - grantedToday).coerceAtLeast(0L),
        )
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

        /** Default house-funded bot-subsidy budget per player per window (launch knob). */
        const val DEFAULT_SUBSIDY_DAILY_CAP = 25_000L

        /** Rolling window the subsidy cap sums over (a "day" without midnight gaming). */
        val DEFAULT_SUBSIDY_WINDOW: kotlin.time.Duration = 24.hours

        const val REASON_BUYIN = "mp_buyin"
        const val REASON_REBUY = "mp_rebuy"
        const val REASON_CASHOUT = "mp_cashout"

        fun buyInKey(sessionId: UUID): String = "table:$sessionId:buyin"
        fun rebuyKey(sessionId: UUID, n: Int): String = "table:$sessionId:rebuy:$n"
        fun cashOutKey(sessionId: UUID): String = "table:$sessionId:cashout"

        /**
         * Audit/display label for the `stake_tier` column. The buy-in itself is
         * the authoritative debited amount ([buyIn]); this is just a readable
         * tag — a named [StakeTier] whose buy-in matches, else `custom:N`.
         */
        fun stakeLabel(buyIn: Long): String =
            StakeTier.entries.firstOrNull { it.buyIn == buyIn }?.name ?: "custom:$buyIn"

        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
