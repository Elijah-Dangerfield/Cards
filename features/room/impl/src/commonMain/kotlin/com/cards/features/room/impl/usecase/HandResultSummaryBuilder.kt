package com.dangerfield.cards.features.room.impl.usecase

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
        val byFoldAround = event.winners.isNotEmpty() && event.winners.all { it.byFold }

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

        val totalPot = event.winners.sumOf { it.amount }

        // "Was the fold actually correct?" — if the human folded AND the dealt
        // board got to enough cards (flop+) to compare, evaluate what the human
        // would have shown vs the strongest revealed hand. Only meaningful for
        // showdown-ending hands; fold-around hands can't be compared.
        val foldedHandWouldHaveLost = if (wasFold && humanSeat != null &&
            humanSeat.holeCards.size == 2 && event.board.size >= 3 && !byFoldAround
        ) {
            val humanRank = HandEvaluator.evaluate(humanSeat.holeCards + event.board)
            val bestRevealed = event.revealedHoleCards
                .filterKeys { it != humanSeatIndex }
                .mapNotNull { (_, holes) ->
                    if (holes.size == 2) HandEvaluator.evaluate(holes + event.board) else null
                }
                .maxOrNull()
            bestRevealed != null && humanRank < bestRevealed
        } else {
            false
        }

        val wonByFold = wonPot && !reachedShowdown
        val humanWasAllIn = humanSeat?.handParticipation == HandParticipation.AllIn ||
            (humanSeat != null && humanSeat.stack <= 0L && humanSeat.contributedThisHand > 0L)

        // Table-composition gate (product-spec.md §5.4). A multiplayer hand
        // on a bot-stacked or solo table is credited at the practice tier —
        // downgrading the mode to BOTS routes it through the halved XP
        // multiplier and drops MP-mode achievements, both of which already
        // key off `summary.mode` downstream. Solo (already BOTS) is untouched.
        val creditMode = if (mode == XpMode.MULTIPLAYER && !MultiplayerCredit.qualifies(state)) {
            XpMode.BOTS
        } else {
            mode
        }

        return HandResultSummary(
            handId = state.handNumber.toString(),
            mode = creditMode,
            wasFold = wasFold,
            reachedShowdown = reachedShowdown,
            wonPot = wonPot,
            chipsCommitted = chipsCommitted,
            bigBlind = state.settings.bigBlind,
            handCategory = categoryGrade,
            totalPot = totalPot,
            foldedHandWouldHaveLost = foldedHandWouldHaveLost,
            wonByFold = wonByFold,
            humanWasAllIn = humanWasAllIn,
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
