package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.BotAvatarEmoji
import com.dangerfield.cards.libraries.cards.Criterion
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit

/**
 * Shared fixtures for `@Preview`-only call sites in this module. Kept
 * `internal` so any file in `features/room/impl` can compose previews
 * without duplicating the SeatView / TableUiState constructors. Not exposed
 * outside the module — these are synthetic, not test data.
 */
internal object PreviewSamples {

    fun card(rank: Rank, suit: Suit): Card = Card(rank, suit)

    fun humanSeat(
        stack: Long = 980,
        contributed: Long = 0,
        isActing: Boolean = false,
        participation: HandParticipation = HandParticipation.InHand,
        holeCards: List<Card> = listOf(
            card(Rank.Ace, Suit.Spades),
            card(Rank.King, Suit.Spades),
        ),
        lastAction: PlayerAction? = null,
        isDealer: Boolean = true,
        isSmallBlind: Boolean = false,
        isBigBlind: Boolean = false,
    ): SeatView = SeatView(
        index = 0,
        displayName = "You",
        stack = stack,
        contributedThisStreet = contributed,
        isActing = isActing,
        isHuman = true,
        isBot = false,
        avatarKey = null,
        emoji = null,
        holeCards = holeCards,
        showHoleCardBacks = false,
        participation = participation,
        seatEmpty = false,
        isBusted = false,
        lastAction = lastAction,
        isDealer = isDealer,
        isSmallBlind = isSmallBlind,
        isBigBlind = isBigBlind,
    )

    fun botSeat(
        index: Int,
        name: String,
        stack: Long = 1000,
        contributed: Long = 0,
        isActing: Boolean = false,
        participation: HandParticipation = HandParticipation.InHand,
        holeCards: List<Card> = emptyList(),
        lastAction: PlayerAction? = null,
        isDealer: Boolean = false,
        isSmallBlind: Boolean = false,
        isBigBlind: Boolean = false,
    ): SeatView = SeatView(
        index = index,
        displayName = name,
        stack = stack,
        contributedThisStreet = contributed,
        isActing = isActing,
        isHuman = false,
        isBot = true,
        avatarKey = "avatar_$name",
        emoji = botEmoji(),
        holeCards = holeCards,
        showHoleCardBacks = participation == HandParticipation.InHand && holeCards.isEmpty(),
        participation = participation,
        seatEmpty = false,
        isBusted = stack <= 0L,
        lastAction = lastAction,
        isDealer = isDealer,
        isSmallBlind = isSmallBlind,
        isBigBlind = isBigBlind,
    )

    fun activeTable(
        street: BettingRound = BettingRound.Preflop,
        communityCards: List<Card> = emptyList(),
        pot: Long = 60,
        seats: List<SeatView> = defaultSeats(),
        actingSeatIndex: Int? = seats.firstOrNull { it.isActing }?.index,
        legalActions: LegalActions? = null,
        humanHandLabel: String? = null,
        handResult: HandResultView? = null,
        handNumber: Int = 1,
        buttonSeatIndex: Int = 0,
        smallBlindSeatIndex: Int? = 1,
        bigBlindSeatIndex: Int? = 2,
        turnTimerSeconds: Int? = null,
    ): TableUiState.Active = TableUiState.Active(
        street = street,
        communityCards = communityCards,
        pot = pot,
        potCommittedThisStreet = pot,
        seats = seats,
        actingSeatIndex = actingSeatIndex,
        isHumanTurn = actingSeatIndex == 0,
        humanLegalActions = legalActions,
        humanHandLabel = humanHandLabel,
        handResult = handResult,
        smallBlind = 10,
        bigBlind = 20,
        handNumber = handNumber,
        buttonSeatIndex = buttonSeatIndex,
        smallBlindSeatIndex = smallBlindSeatIndex,
        bigBlindSeatIndex = bigBlindSeatIndex,
        turnTimerSeconds = turnTimerSeconds,
    )

    fun defaultSeats(): List<SeatView> = listOf(
        humanSeat(isActing = true, isDealer = true),
        botSeat(index = 1, name = "David", isSmallBlind = true, contributed = 10),
        botSeat(index = 2, name = "Jane", isBigBlind = true, contributed = 20),
        botSeat(index = 3, name = "Mike"),
    )

    fun legalActions(
        canCheck: Boolean = false,
        canCall: Boolean = true,
        callAmount: Long = 20,
        canRaise: Boolean = true,
        isOpenBet: Boolean = false,
        minRaiseTotal: Long = 40,
        maxRaiseTotal: Long = 980,
    ): LegalActions = LegalActions(
        canCheck = canCheck,
        canCall = canCall,
        callAmount = callAmount,
        canRaise = canRaise,
        isOpenBet = isOpenBet,
        minRaiseTotal = minRaiseTotal,
        maxRaiseTotal = maxRaiseTotal,
        canAllIn = true,
        allInAmount = maxRaiseTotal,
        potIfYouCall = 50 + callAmount,
    )

    fun flopBoard(): List<Card> = listOf(
        card(Rank.Ten, Suit.Hearts),
        card(Rank.Seven, Suit.Clubs),
        card(Rank.Two, Suit.Diamonds),
    )

    fun turnBoard(): List<Card> = flopBoard() + card(Rank.Five, Suit.Spades)

    fun riverBoard(): List<Card> = turnBoard() + card(Rank.Ace, Suit.Diamonds)

    fun earnedAchievement(
        id: AchievementId = AchievementId.HANDS_10,
        name: String = "Getting started",
        description: String = "Play 10 hands.",
        icon: String = "🎯",
        rarity: AchievementRarity = AchievementRarity.COMMON,
        xpReward: Int = 50,
        chipReward: Long = 0L,
    ): EarnedAchievement = EarnedAchievement(
        achievement = Achievement(
            id = id,
            name = name,
            description = description,
            icon = icon,
            rarity = rarity,
            criterion = Criterion.HandsPlayed(10),
            xpReward = xpReward,
            chipReward = chipReward,
        ),
        earnedAtEpochMs = 0L,
    )

    fun handWinner(
        seatIndex: Int = 0,
        amount: Long = 240,
        byFold: Boolean = false,
    ): HandWinner = HandWinner(
        seatIndex = seatIndex,
        amount = amount,
        handRank = null,
        byFold = byFold,
    )

    // Every bot reads as a bot at the table — previews match runtime.
    private fun botEmoji(): String = BotAvatarEmoji
}
