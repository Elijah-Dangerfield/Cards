package com.dangerfield.cards.server.game

/**
 * Between-hands lifecycle for a table that auto-advances. When a hand completes
 * and the table can deal another, [ServerBotDriver] holds the deal for a short
 * server-config beat and announces the deadline here so clients render an honest
 * "Next hand in 0:0X" countdown without the server streaming ticks — the same
 * deadline-broadcast shape [MatchOverEvent.GraceStarted] uses for the heads-up
 * rebuy window. Forwarded to clients by the socket fan-out.
 *
 * The server deals the next hand exactly when the beat elapses, so the client
 * countdown can never be a lie the server deals under (a client-only timer
 * would be). Kept distinct from the poker engine's `GameState`: the between-hands
 * pause is a table concern, not a hand concern, so the engine stays unaware of it.
 */
sealed interface NextHandEvent {
    /**
     * The current hand finished and the next one is held until [deadlineEpochMs]
     * (wall-clock). Both the felt countdown and the server's own deal fire off
     * this instant, so they stay in lockstep.
     */
    data class Pending(val deadlineEpochMs: Long) : NextHandEvent

    /**
     * The pending advance was cancelled before it dealt — the table can no longer
     * deal a next hand (a player left or busted it down to a single survivor, who
     * now waits on the heads-up rebuy grace instead). Clears the countdown.
     */
    data object Cleared : NextHandEvent
}
