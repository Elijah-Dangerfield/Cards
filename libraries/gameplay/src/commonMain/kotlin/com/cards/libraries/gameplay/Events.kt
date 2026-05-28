package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class GameEvent {
    abstract val sequence: Long

    @Serializable
    @SerialName("HandStarted")
    data class HandStarted(
        override val sequence: Long,
        val handNumber: Int,
        val buttonSeatIndex: Int,
    ) : GameEvent()

    @Serializable
    @SerialName("BlindPosted")
    data class BlindPosted(
        override val sequence: Long,
        val seatIndex: Int,
        val amount: Long,
        val isSmall: Boolean,
    ) : GameEvent()

    @Serializable
    @SerialName("HoleCardsDealt")
    data class HoleCardsDealt(
        override val sequence: Long,
        val seatIndex: Int,
        val cards: List<Card>,
    ) : GameEvent()

    @Serializable
    @SerialName("StreetAdvanced")
    data class StreetAdvanced(
        override val sequence: Long,
        val street: BettingRound,
        val communityCards: List<Card>,
    ) : GameEvent()

    @Serializable
    @SerialName("ActionTaken")
    data class ActionTaken(
        override val sequence: Long,
        val seatIndex: Int,
        val action: PlayerAction,
        val resultingStreetContribution: Long,
    ) : GameEvent()

    @Serializable
    @SerialName("PotAwarded")
    data class PotAwarded(
        override val sequence: Long,
        val seatIndex: Int,
        val amount: Long,
        val potIndex: Int,
        val withCategory: HandCategory?,
    ) : GameEvent()

    @Serializable
    @SerialName("HandEnded")
    data class HandEnded(
        override val sequence: Long,
        val winners: List<HandWinner>,
        val board: List<Card>,
        val revealedHoleCards: Map<Int, List<Card>>,
    ) : GameEvent()
}

@Serializable
sealed class PlayerAction {
    @Serializable
    @SerialName("Fold")
    data object Fold : PlayerAction()

    @Serializable
    @SerialName("Check")
    data object Check : PlayerAction()

    @Serializable
    @SerialName("Call")
    data class Call(val amount: Long) : PlayerAction()

    @Serializable
    @SerialName("Bet")
    data class Bet(val amount: Long) : PlayerAction()

    @Serializable
    @SerialName("Raise")
    data class Raise(val totalStreetContribution: Long, val raiseAmount: Long) : PlayerAction()

    @Serializable
    @SerialName("AllIn")
    data class AllIn(val amount: Long) : PlayerAction()
}

@Serializable
enum class BettingRound {
    Preflop,
    Flop,
    Turn,
    River,
    Showdown,
    Complete;

    val communityCardCount: Int
        get() = when (this) {
            Preflop -> 0
            Flop -> 3
            Turn -> 4
            River, Showdown, Complete -> 5
        }
}

@Serializable
data class HandWinner(
    val seatIndex: Int,
    val amount: Long,
    val handRank: HandRank?,
    val byFold: Boolean,
)
