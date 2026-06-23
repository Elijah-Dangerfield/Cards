package com.dangerfield.cards.server.domain

import java.util.UUID

/**
 * Coordinates the chip movements that pair with a [TableSession]: the
 * buy-in debit on sit-down, the top-up debit on rebuy, and the refund
 * credit on cash-out. Every movement goes through the wallet ledger keyed
 * off the session id, so a full sit nets to `cashout − buyin − Σrebuys`
 * and any retried movement collapses to a no-op.
 *
 * Real money never reaches the table: these are virtual chips bought with
 * IAP and never cashed out (see the plan). "Buy-in" / "cash-out" move
 * chips between the player's [Wallet] and their in-RAM table stack — not
 * between the player and money.
 *
 * The buy-in is the room's own [Room.buyIn] (develop shipped a free-form
 * per-room buy-in; there is no fixed stake-tier on the room). [enforceEntryBar]
 * is the anti-bankroll-dump guard (wallet must cover ≥ 4 buy-ins) — applied to
 * public matchmaking tables and skipped for private friend games, the caller
 * decides from the room's visibility.
 */
interface TableSessionService {

    /**
     * Reserve a seat in [roomCode] for [userId], moving [buyIn] from wallet →
     * table stack. Bots never call this — they have no wallet, only an engine
     * stack. When [enforceEntryBar] the wallet must hold ≥ 4 × [buyIn] to sit.
     */
    suspend fun sitDown(
        userId: UserId,
        roomCode: String,
        buyIn: Long,
        enforceEntryBar: Boolean = true,
    ): SitDownResult

    /**
     * Top the player's stack back up to one buy-in after a bust, debiting the
     * wallet by the session's frozen buy-in. [enforceEntryBar] re-applies the
     * same 4× guard as sit-down (the caller passes the room's policy).
     */
    suspend fun rebuy(userId: UserId, enforceEntryBar: Boolean = true): RebuyResult

    /**
     * Settle the player's active session: credit their stack back to the
     * wallet and close the row. [finalStack] is their live table stack at
     * stand-up / bust / disconnect; pass `null` when no live stack is known
     * (they sat but no hand was ever dealt, or we're recovering a crashed
     * session with no snapshot seat) and the full funded amount
     * (`buyIn × (1 + rebuyCount)`) is refunded instead.
     *
     * Idempotent and safe under any timing: the credit is keyed per session
     * and the row closes, so a second call once closed is a
     * [CashOutResult.NoActiveSession] — chips are credited at most once.
     * Leaving mid-hand forfeits chips already committed to the pot (they're
     * excluded from the live stack), which is the correct poker semantics.
     */
    suspend fun cashOut(userId: UserId, finalStack: Long?): CashOutResult
}

sealed interface SitDownResult {
    /** Real-stakes seat funded: [startingStack] moved wallet → table; [balanceAfter] is the post-debit wallet. */
    data class Funded(val sessionId: UUID, val startingStack: Long, val balanceAfter: Long) : SitDownResult

    /** Double-spend guard tripped — the player already holds an active session in [roomCode]. */
    data class AlreadyAtTable(val roomCode: String) : SitDownResult

    /** Anti-smurf entry bar: the buy-in would exceed 25% of the wallet. [minBalance] is what's required. */
    data class BelowEntryBar(val balance: Long, val minBalance: Long) : SitDownResult

    /** Balance dropped below the buy-in between the entry-bar read and the atomic debit. */
    data class InsufficientChips(val balance: Long) : SitDownResult
}

sealed interface RebuyResult {
    data class ReboughtIn(val startingStack: Long, val balanceAfter: Long) : RebuyResult
    data class BelowEntryBar(val balance: Long, val minBalance: Long) : RebuyResult
    data class InsufficientChips(val balance: Long) : RebuyResult
    data object NoActiveSession : RebuyResult
}

sealed interface CashOutResult {
    data class CashedOut(val refunded: Long, val balanceAfter: Long) : CashOutResult
    data object NoActiveSession : CashOutResult
}
