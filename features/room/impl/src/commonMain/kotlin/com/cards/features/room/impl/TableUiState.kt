package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.Seat

sealed interface TableUiState {
    data object Loading : TableUiState

    data class Active(
        val street: BettingRound,
        val communityCards: List<Card>,
        val pot: Long,
        val seats: List<SeatView>,
        val actingSeatIndex: Int?,
        val isHumanTurn: Boolean,
        val humanLegalActions: LegalActions?,
        val humanHandLabel: String?,
        val lastBotThoughts: Map<Int, BotThought>,
        val handResult: HandResultView?,
        val smallBlind: Long,
        val bigBlind: Long,
        val handNumber: Int,
    ) : TableUiState

    companion object {
        fun fromGameState(
            gameState: GameState,
            humanSeatIndex: Int,
            personalitiesBySeat: Map<Int, BotPersonality>,
            lastThoughts: Map<Int, BotThought>,
            lastWinners: GameEvent.HandEnded?,
        ): Active {
            val pot = gameState.seats.sumOf { it.contributedThisStreet } +
                gameState.pots.sumOf { it.amount }
            val acting = gameState.actingSeatIndex
            val isHumanTurn = acting == humanSeatIndex
            val seats = gameState.seats.map { seat ->
                SeatView.fromSeat(
                    seat = seat,
                    isActing = seat.index == acting,
                    isHuman = seat.index == humanSeatIndex,
                    personality = personalitiesBySeat[seat.index],
                    hideHoleCards = seat.index != humanSeatIndex && lastWinners == null,
                    revealedHoleCards = lastWinners?.revealedHoleCards?.get(seat.index),
                )
            }
            val humanSeat = gameState.seats.firstOrNull { it.index == humanSeatIndex }
            val legal = if (isHumanTurn && humanSeat != null) LegalActions.from(gameState, humanSeat) else null
            val humanHandLabel = humanSeat?.let { previewHandLabel(it.holeCards, gameState.community) }
            val result = lastWinners?.let { ev ->
                HandResultView(
                    winners = ev.winners,
                    board = ev.board,
                )
            }
            return Active(
                street = gameState.street,
                communityCards = gameState.community,
                pot = pot,
                seats = seats,
                actingSeatIndex = acting,
                isHumanTurn = isHumanTurn,
                humanLegalActions = legal,
                humanHandLabel = humanHandLabel,
                lastBotThoughts = lastThoughts,
                handResult = result,
                smallBlind = gameState.settings.smallBlind,
                bigBlind = gameState.settings.bigBlind,
                handNumber = gameState.handNumber,
            )
        }
    }
}

data class SeatView(
    val index: Int,
    val displayName: String,
    val stack: Long,
    val contributedThisStreet: Long,
    val isActing: Boolean,
    val isHuman: Boolean,
    val isBot: Boolean,
    val avatarKey: String?,
    val holeCards: List<Card>,
    val showHoleCardBacks: Boolean,
    val participation: HandParticipation,
    val seatEmpty: Boolean,
) {
    companion object {
        fun fromSeat(
            seat: Seat,
            isActing: Boolean,
            isHuman: Boolean,
            personality: BotPersonality?,
            hideHoleCards: Boolean,
            revealedHoleCards: List<Card>?,
        ): SeatView {
            val visibleHole = when {
                seat.handParticipation == HandParticipation.NotDealt -> emptyList()
                isHuman -> seat.holeCards
                revealedHoleCards != null -> revealedHoleCards
                hideHoleCards -> emptyList()
                else -> seat.holeCards
            }
            val backs = !isHuman &&
                seat.handParticipation != HandParticipation.NotDealt &&
                seat.handParticipation != HandParticipation.Folded &&
                visibleHole.isEmpty()
            return SeatView(
                index = seat.index,
                displayName = seat.displayName,
                stack = seat.stack,
                contributedThisStreet = seat.contributedThisStreet,
                isActing = isActing,
                isHuman = isHuman,
                isBot = seat.isBot,
                avatarKey = personality?.avatarKey,
                holeCards = visibleHole,
                showHoleCardBacks = backs,
                participation = seat.handParticipation,
                seatEmpty = seat.playerId == null,
            )
        }
    }
}

data class LegalActions(
    val canCheck: Boolean,
    val canCall: Boolean,
    val callAmount: Long,
    val canRaise: Boolean,
    val minRaiseTotal: Long,
    val maxRaiseTotal: Long,
    val canAllIn: Boolean,
    val allInAmount: Long,
    val potIfYouCall: Long,
) {
    companion object {
        fun from(state: GameState, seat: Seat): LegalActions {
            val toCall = (state.currentBetThisStreet - seat.contributedThisStreet).coerceAtLeast(0)
            val canCheck = toCall == 0L
            val canCall = toCall in 1..seat.stack
            val canRaise = seat.stack > toCall
            val minRaiseTotal = if (state.currentBetThisStreet == 0L) {
                state.settings.bigBlind
            } else {
                state.currentBetThisStreet + state.lastFullRaiseSize
            }
            val maxRaiseTotal = seat.contributedThisStreet + seat.stack
            val pot = state.seats.sumOf { it.contributedThisStreet } +
                state.pots.sumOf { it.amount }
            return LegalActions(
                canCheck = canCheck,
                canCall = canCall,
                callAmount = toCall,
                canRaise = canRaise && maxRaiseTotal >= minRaiseTotal,
                minRaiseTotal = minRaiseTotal,
                maxRaiseTotal = maxRaiseTotal,
                canAllIn = seat.stack > 0,
                allInAmount = seat.stack,
                potIfYouCall = pot + toCall,
            )
        }
    }
}

data class HandResultView(
    val winners: List<HandWinner>,
    val board: List<Card>,
)

private fun previewHandLabel(holeCards: List<Card>, community: List<Card>): String? {
    if (holeCards.size != 2) return null
    val total = holeCards + community
    return when {
        total.size == 2 -> if (holeCards[0].rank == holeCards[1].rank) "Pocket pair" else null
        total.size in 5..7 -> HandEvaluator.evaluate(total).category.displayName
        else -> null
    }
}
