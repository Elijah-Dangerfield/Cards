package com.dangerfield.cards.server.domain

import java.util.UUID

/**
 * Public-matchmaking domain types. The matchmaker is find-or-create, not a
 * queue: it seats a searcher into the best eligible open/public room within
 * their buy-in range, else opens a fresh public table for them (no bots yet —
 * we genuinely wait for real humans).
 */

/** Outcome of [RoomService.findOrJoinPublic]. Both carry the room to connect to;
 *  the distinction drives the created-vs-joined "is there organic density yet?"
 *  metric. */
sealed interface MatchmakingResult {
    val room: Room

    /** Seated into an existing eligible room (Open or Public). */
    data class Joined(override val room: Room) : MatchmakingResult

    /** No eligible room — opened a fresh Public table seated with just this player. */
    data class Created(override val room: Room) : MatchmakingResult
}

/**
 * Single source of truth for the anti-bankroll-dump entry bar: a public table's
 * buy-in must be ≤ 25% of the wallet, i.e. the wallet covers at least four
 * buy-ins. Both the real [com.dangerfield.cards.server.data.DefaultTableSessionService]
 * (sit-down + rebuy) and affordable matchmaking ([RoomService.findOrJoinPublic])
 * read it, so the seat gate and the tables the matchmaker offers can never drift
 * apart — the exact drift that stranded fresh players in Lobby forever when the
 * matchmaker snapped them to a 5,000 table their 10,000 grant couldn't fund.
 */
object EntryBar {
    /** Wallet must cover ≥ this many buy-ins to enter (or rebuy at) a tier — the 25% rule. */
    const val MIN_BALANCE_BUYIN_MULTIPLE = 4L

    /** Whether [balance] clears the entry bar for a table at [buyIn]. */
    fun canSit(balance: Long, buyIn: Long): Boolean = balance >= buyIn * MIN_BALANCE_BUYIN_MULTIPLE

    /** The smallest balance that clears the entry bar for a table at [buyIn]. */
    fun minBalanceToSit(buyIn: Long): Long = buyIn * MIN_BALANCE_BUYIN_MULTIPLE

    /** The largest buy-in [balance] can clear the entry bar for. */
    fun maxAffordableBuyIn(balance: Long): Long = balance / MIN_BALANCE_BUYIN_MULTIPLE
}

/**
 * Synthetic host for matchmaker-created public tables. No human owns a public
 * room — the server deals it — so its `hostUserId` is this fixed sentinel rather
 * than a player. It is never a *member* (never seated), has no auth.users row,
 * and being constant means the per-host room cap can't apply to public tables
 * (matchmaking uses a separate global cap instead).
 */
val SYSTEM_HOST_USER_ID: UserId = UserId(UUID(0L, 0L))
