package com.dangerfield.cards.server.domain

import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One sit-down at a real-stakes multiplayer table — the durable
 * interlock between a player's in-RAM table stack and their persistent
 * chip [Wallet]. See `V60__table_sessions.sql`.
 *
 * The buy-in debit, every rebuy debit, and the cash-out credit are
 * [WalletEvent]s keyed off [sessionId] (`table:{sessionId}:buyin`,
 * `:rebuy:{n}`, `:cashout`) so a sit always nets correctly and a retry
 * collapses to a no-op. This row is lifecycle bookkeeping, not a second
 * money ledger; [status] is the crash/disconnect interlock.
 */
@OptIn(ExperimentalTime::class)
data class TableSession(
    val sessionId: UUID,
    val userId: UserId,
    val roomCode: String,
    /**
     * Stake-tier name at open time (see `StakeTier`). Audit/display only;
     * [buyIn] is the authoritative debited amount.
     */
    val stakeTier: String,
    /**
     * Chips debited on sit-down (== the tier's buy-in at open time).
     * Frozen here so a later tier recalibration can't change what this
     * session actually owes or refunds.
     */
    val buyIn: Long,
    val rebuyCount: Int,
    val status: TableSessionStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
    /** This session ran on a disclosed-bot subsidy table (public, lone human vs bots). */
    val subsidized: Boolean = false,
    /** Net house-funded chips granted on cash-out — the input to the per-user daily cap. */
    val subsidyGranted: Long = 0,
)

/**
 * Lifecycle of a [TableSession]. `Open` → `Closing` → `Closed` is
 * forward-only; "active" means not [Closed] (the partial indexes in
 * `V60` key off `status <> 'closed'`). [dbValue] is the wire/column
 * spelling, kept on the enum so the repository and the table-session
 * service share one source of truth for the strings.
 */
enum class TableSessionStatus(val dbValue: String) {
    Open("open"),
    Closing("closing"),
    Closed("closed"),
    ;

    companion object {
        fun fromDb(value: String): TableSessionStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: error("Unknown table_session status: $value")
    }
}

/**
 * Persistence for [TableSession] rows. Pure row bookkeeping — the chip
 * movements that pair with these rows go through [WalletRepository.apply],
 * and the buy-in's row-insert is done by the table-session service inside
 * the same transaction as the debit (so the two commit atomically). This
 * layer owns reads, the forward-only status flips, the rebuy counter, the
 * boot-sweep listing, and the account-delete cascade.
 */
@OptIn(ExperimentalTime::class)
interface TableSessionRepository {

    suspend fun find(sessionId: UUID): TableSession?

    /** The user's current non-closed session, if any (double-spend guard read). */
    suspend fun findActiveForUser(userId: UserId): TableSession?

    /** Non-closed sessions for a room — cash-out paths fan out over these. */
    suspend fun findActiveByRoom(roomCode: String): List<TableSession>

    /** Flip `open → closing`. Returns true only on the flip; idempotent no-op otherwise. */
    suspend fun markClosing(sessionId: UUID): Boolean

    /** Flip `closing → closed`, stamping `closed_at`. Returns true only on the flip. */
    suspend fun markClosed(sessionId: UUID): Boolean

    /** Bump `rebuy_count`, returning the new count (used in the rebuy ledger key). */
    suspend fun incrementRebuy(sessionId: UUID): Int

    /** Record the net house-funded win on a subsidised session at cash-out (the daily-cap input). */
    suspend fun recordSubsidyGranted(sessionId: UUID, amount: Long)

    /**
     * Sum of subsidy granted to [userId] across sessions closed at or after
     * [since] — the rolling-window draw-down the sit-down cap reads. Only closed
     * sessions count, so an in-flight subsidised session never blocks itself.
     */
    suspend fun subsidyGrantedSince(userId: UserId, since: Instant): Long

    /** Every non-closed session across all rooms — the boot sweep cashes these out. */
    suspend fun listActive(): List<TableSession>

    /** Account-delete cascade. */
    suspend fun deleteAllForUser(userId: UserId)
}
