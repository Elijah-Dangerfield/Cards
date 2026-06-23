package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.EquityBreakdown
import com.dangerfield.cards.libraries.bots.HandStrength
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation

/**
 * Win-odds (equity) derivation, split out of [PlayPokerViewModel] so the gating
 * — which decides *whether* and *with what inputs* to run the Monte Carlo — is
 * unit-testable without the VM's flows or coroutine scope. The VM keeps the
 * subscription plumbing (combine / distinctUntilChanged / mapLatest /
 * off-main-thread dispatch); this object owns the pure decision.
 */
object WinOddsEngine {

    sealed interface EquityInput {
        data object NotApplicable : EquityInput
        data class Compute(
            val hole: List<Card>,
            val community: List<Card>,
            val opponents: Int,
        ) : EquityInput
    }

    /**
     * Derive the equity input from the live state. Returns [EquityInput.NotApplicable]
     * — meaning win-odds shouldn't run / should clear — when: the tool isn't
     * equipped, the human isn't holding exactly two cards (pre-deal, folded, or
     * not seated), or no opponents remain in the hand. Otherwise an
     * [EquityInput.Compute] carrying the human's hole cards, the visible board,
     * and the count of opponents still contesting (in-hand or all-in).
     */
    fun inputFor(
        state: GameState,
        humanSeatIndex: Int,
        toolEquipped: Boolean,
    ): EquityInput {
        if (!toolEquipped) return EquityInput.NotApplicable
        val human = state.seats.firstOrNull { it.index == humanSeatIndex }
            ?: return EquityInput.NotApplicable
        if (human.holeCards.size != 2) return EquityInput.NotApplicable
        val opponents = state.seats.count { seat ->
            seat.index != humanSeatIndex &&
                (seat.handParticipation == HandParticipation.InHand ||
                    seat.handParticipation == HandParticipation.AllIn)
        }
        if (opponents == 0) return EquityInput.NotApplicable
        return EquityInput.Compute(
            hole = human.holeCards,
            community = state.community,
            opponents = opponents,
        )
    }

    /**
     * Run the Monte Carlo equity estimate for a [EquityInput.Compute]. CPU-bound
     * (≈[iterations] hand evaluations) — callers run it off the main thread.
     */
    fun compute(input: EquityInput.Compute, iterations: Int): EquityBreakdown =
        HandStrength.equityBreakdownVsRandom(
            holeCards = input.hole,
            community = input.community,
            numOpponents = input.opponents,
            iterations = iterations,
        )
}
