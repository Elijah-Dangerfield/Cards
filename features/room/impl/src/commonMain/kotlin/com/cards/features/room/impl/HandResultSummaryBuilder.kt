package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandCategory
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation

/**
 * Translates an engine `HandEnded` event + the table state into the
 * mode-agnostic [HandResultSummary] consumed by `ProgressionRepository`.
 *
 * Lives in the room feature because it has to know about poker semantics
 * (hand evaluation, fold detection). When multiplayer ships, the same
 * translation runs server-side — but the input shape is the same.
 */
internal object HandResultSummaryBuilder {

    fun build(
        event: GameEvent.HandEnded,
        state: GameState,
        humanSeatIndex: Int,
        mode: XpMode,
    ): HandResultSummary {
        val humanSeat = state.seats.firstOrNull { it.index == humanSeatIndex }
        val wasFold = humanSeat?.handParticipation == HandParticipation.Folded
        val humanRevealedCards = event.revealedHoleCards[humanSeatIndex]
        val reachedShowdown = !wasFold && humanRevealedCards != null
        val wonPot = event.winners.any { it.seatIndex == humanSeatIndex }
        val chipsCommitted = humanSeat?.contributedThisHand ?: 0L

        val categoryGrade: HandCategoryGrade? = if (reachedShowdown) {
            val winningRank = event.winners
                .firstOrNull { it.seatIndex == humanSeatIndex && !it.byFold }
                ?.handRank
                ?.category
            val resolved = winningRank ?: humanRevealedCards
                ?.takeIf { it.size == 2 }
                ?.let { HandEvaluator.evaluate(it + event.board).category }
            resolved?.toGrade()
        } else {
            null
        }

        return HandResultSummary(
            handId = state.handNumber.toString(),
            mode = mode,
            wasFold = wasFold,
            reachedShowdown = reachedShowdown,
            wonPot = wonPot,
            chipsCommitted = chipsCommitted,
            bigBlind = state.settings.bigBlind,
            handCategory = categoryGrade,
        )
    }

    private fun HandCategory.toGrade(): HandCategoryGrade = when (this) {
        HandCategory.HighCard -> HandCategoryGrade.HighCard
        HandCategory.Pair -> HandCategoryGrade.Pair
        HandCategory.TwoPair -> HandCategoryGrade.TwoPair
        HandCategory.ThreeOfAKind -> HandCategoryGrade.ThreeOfAKind
        HandCategory.Straight -> HandCategoryGrade.Straight
        HandCategory.Flush -> HandCategoryGrade.Flush
        HandCategory.FullHouse -> HandCategoryGrade.FullHouse
        HandCategory.FourOfAKind -> HandCategoryGrade.FourOfAKind
        HandCategory.StraightFlush -> HandCategoryGrade.StraightFlush
        HandCategory.RoyalFlush -> HandCategoryGrade.RoyalFlush
    }
}
