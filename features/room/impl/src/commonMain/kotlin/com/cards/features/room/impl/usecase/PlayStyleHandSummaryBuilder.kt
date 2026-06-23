package com.dangerfield.cards.features.room.impl.usecase

import com.dangerfield.cards.libraries.cards.PlayStyleHandSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandCategory
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction

/**
 * Accumulates the human seat's actions across one hand, then turns them into a
 * [PlayStyleHandSummary] at hand-end. The four play-style axes need per-action
 * signals (preflop entry, aggression counts) the engine doesn't retain in
 * [GameState], so we tally them off the event stream as the hand plays out.
 *
 * Lives in the room feature because it knows poker semantics (streets, action
 * types, hand strength) — mirrors [HandResultSummaryBuilder]. One instance per
 * [com.dangerfield.cards.features.room.impl.PlayPokerViewModel]: [reset] on
 * `HandStarted`, fed each `BlindPosted` / `StreetAdvanced` / `ActionTaken`,
 * finalized by [build] on `HandEnded`.
 *
 * NOT thread-safe — drive it from a single event collector so actions are seen
 * in order before the hand's `HandEnded`.
 */
internal class PlayStyleHandSummaryBuilder {

    private var currentStreet: BettingRound = BettingRound.Preflop
    private var inBlind = false
    private var vpip = false
    private var pfr = false
    private var preflopFold = false
    private var aggressiveActionCount = 0
    private var callActionCount = 0
    private var postflopAggressiveCount = 0

    fun reset() {
        currentStreet = BettingRound.Preflop
        inBlind = false
        vpip = false
        pfr = false
        preflopFold = false
        aggressiveActionCount = 0
        callActionCount = 0
        postflopAggressiveCount = 0
    }

    fun onBlindPosted(event: GameEvent.BlindPosted, humanSeatIndex: Int) {
        if (event.seatIndex == humanSeatIndex) inBlind = true
    }

    fun onStreetAdvanced(event: GameEvent.StreetAdvanced) {
        currentStreet = event.street
    }

    fun onActionTaken(event: GameEvent.ActionTaken, humanSeatIndex: Int) {
        if (event.seatIndex != humanSeatIndex) return
        val preflop = currentStreet == BettingRound.Preflop
        when (event.action) {
            is PlayerAction.Fold -> {
                // A clean preflop laydown (folded without first entering the pot)
                // is the discipline signal; a limp-then-fold doesn't count.
                if (preflop && !vpip) preflopFold = true
            }
            is PlayerAction.Check -> Unit
            is PlayerAction.Call -> {
                callActionCount++
                if (preflop) vpip = true
            }
            // All-in is folded into aggression — it applies pressure regardless
            // of whether it was technically a call-all-in.
            is PlayerAction.Bet, is PlayerAction.Raise, is PlayerAction.AllIn -> {
                aggressiveActionCount++
                if (preflop) {
                    vpip = true
                    pfr = true
                } else {
                    postflopAggressiveCount++
                }
            }
        }
    }

    /**
     * Build the hand's contribution, or null if the human wasn't dealt into the
     * hand (a spectator / sitting-out seat contributes nothing).
     */
    fun build(
        event: GameEvent.HandEnded,
        state: GameState,
        humanSeatIndex: Int,
        mode: XpMode,
    ): PlayStyleHandSummary? {
        val humanSeat = state.seats.firstOrNull { it.index == humanSeatIndex } ?: return null
        if (humanSeat.handParticipation == HandParticipation.NotDealt) return null

        val wasFold = humanSeat.handParticipation == HandParticipation.Folded
        val revealed = event.revealedHoleCards[humanSeatIndex]
        val reachedShowdown = !wasFold && revealed != null
        val wonPot = event.winners.any { it.seatIndex == humanSeatIndex }

        // Showdown bluff: drove postflop aggression into a showdown holding only
        // a weak made hand (high card / pair) and lost.
        val showdownBluff = reachedShowdown && !wonPot && postflopAggressiveCount > 0 && run {
            val cards = revealed?.takeIf { it.size == 2 }
                ?: humanSeat.holeCards.takeIf { it.size == 2 }
            val category = cards?.let { HandEvaluator.evaluate(it + event.board).category }
            category != null && category.ordinal <= HandCategory.Pair.ordinal
        }

        return PlayStyleHandSummary(
            handId = state.handNumber.toString(),
            mode = mode,
            inBlind = inBlind,
            vpip = vpip,
            pfr = pfr,
            preflopFold = preflopFold,
            aggressiveActionCount = aggressiveActionCount,
            callActionCount = callActionCount,
            wentToShowdown = reachedShowdown,
            showdownBluff = showdownBluff,
        )
    }
}
