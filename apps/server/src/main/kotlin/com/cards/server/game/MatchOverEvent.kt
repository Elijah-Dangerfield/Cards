package com.dangerfield.cards.server.game

/**
 * Lifecycle of a heads-up table that has run down to a single player with chips.
 * Orchestrated by [MatchOverGraceDriver] and forwarded to clients by the socket
 * fan-out. Kept distinct from the poker engine's `GameState` on purpose: a grace
 * window and a terminal resolution are table concerns, not hand concerns, so the
 * engine stays unaware of them.
 */
sealed interface MatchOverEvent {
    /**
     * The busted player has a grace window to rebuy before the match resolves.
     * [deadlineEpochMs] is the wall-clock instant the window closes, so clients
     * can render a live countdown without the server streaming ticks.
     */
    data class GraceStarted(
        val deadlineEpochMs: Long,
        val bustedSeatIndex: Int,
        val winnerSeatIndex: Int,
    ) : MatchOverEvent

    /** The busted player rebought (or the table otherwise recovered) — clear the countdown. */
    data object GraceCancelled : MatchOverEvent

    /** Grace expired with no rebuy: the match is over and [winnerUserId] takes the table. */
    data class Resolved(val winnerUserId: String) : MatchOverEvent
}
