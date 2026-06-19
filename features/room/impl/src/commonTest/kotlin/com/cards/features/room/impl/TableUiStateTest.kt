package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.BotAvatarEmoji
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableUiStateTest {

    @Test
    fun allInWithZeroStackMidHand_isNotBusted() {
        val table = activeFromSeats(
            street = BettingRound.Flop,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.AllIn),
            ),
        )
        val allIn = table.seats.single { it.index == 1 }
        assertFalse(allIn.isBusted, "AllIn with 0 stack mid-hand must not be busted")
    }

    @Test
    fun allInLoserAtComplete_isBusted() {
        val table = activeFromSeats(
            street = BettingRound.Complete,
            seats = listOf(
                seat(index = 0, stack = 2_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.AllIn),
            ),
        )
        val loser = table.seats.single { it.index == 1 }
        assertTrue(loser.isBusted, "AllIn loser at Complete should read as busted")
    }

    @Test
    fun zeroStackNotDealt_isBusted() {
        val table = activeFromSeats(
            street = BettingRound.Preflop,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.NotDealt),
            ),
        )
        val sittingOut = table.seats.single { it.index == 1 }
        assertTrue(sittingOut.isBusted, "Sat-out (NotDealt) with 0 stack reads as busted between hands")
    }

    @Test
    fun shortLabel_compactlyFormatsChipAmounts() {
        assertEquals("Folded", PlayerAction.Fold.shortLabel())
        assertEquals("Checked", PlayerAction.Check.shortLabel())
        assertEquals("Called 20", PlayerAction.Call(amount = 20L).shortLabel())
        assertEquals("Bet 500", PlayerAction.Bet(amount = 500L).shortLabel())
        assertEquals(
            "Raised to 1.5k",
            PlayerAction.Raise(totalStreetContribution = 1_500L, raiseAmount = 500L).shortLabel(),
        )
        assertEquals("All in 12k", PlayerAction.AllIn(amount = 12_000L).shortLabel())
        assertEquals("Called 1M", PlayerAction.Call(amount = 1_000_000L).shortLabel())
    }

    @Test
    fun badgeFor_humanWithKnownLevel_isLevelBadge() {
        val seat = seat(index = 0, stack = 1_000, participation = HandParticipation.InHand)
        val badge = TableUiState.badgeFor(
            seat = seat,
            isHuman = true,
            humanLevel = 14,
            botDifficultyLabel = null,
        )
        assertEquals(SeatBadge.Level(14), badge)
    }

    @Test
    fun badgeFor_humanWithoutLevel_isNull() {
        val seat = seat(index = 0, stack = 1_000, participation = HandParticipation.InHand)
        val badge = TableUiState.badgeFor(
            seat = seat,
            isHuman = true,
            humanLevel = null,
            botDifficultyLabel = "Standard",
        )
        assertEquals(null, badge)
    }

    @Test
    fun badgeFor_botWithDifficulty_isBotWithDifficulty() {
        val seat = botSeat(index = 1)
        val badge = TableUiState.badgeFor(
            seat = seat,
            isHuman = false,
            humanLevel = null,
            botDifficultyLabel = "Challenging",
        )
        assertEquals(SeatBadge.BotWithDifficulty("Challenging"), badge)
    }

    @Test
    fun badgeFor_botWithoutDifficulty_isBotPlain() {
        val seat = botSeat(index = 2)
        val badge = TableUiState.badgeFor(
            seat = seat,
            isHuman = false,
            humanLevel = null,
            botDifficultyLabel = null,
        )
        assertEquals(SeatBadge.BotPlain, badge)
    }

    @Test
    fun badgeFor_emptySeat_isNull() {
        val empty = seat(
            index = 1,
            stack = 0,
            participation = HandParticipation.NotDealt,
            empty = true,
        )
        val badge = TableUiState.badgeFor(
            seat = empty,
            isHuman = false,
            humanLevel = 14,
            botDifficultyLabel = "Standard",
        )
        assertEquals(null, badge)
    }

    @Test
    fun badgeFor_remoteHumanInMp_isNull() {
        // Non-bot, non-local-human seat — MP plumbing for a remote human's
        // level isn't wired yet, so the badge collapses to null.
        val remoteHuman = seat(index = 1, stack = 1_000, participation = HandParticipation.InHand)
        val badge = TableUiState.badgeFor(
            seat = remoteHuman,
            isHuman = false,
            humanLevel = 30,
            botDifficultyLabel = "Standard",
        )
        assertEquals(null, badge)
    }

    @Test
    fun botSeat_projectsReservedRobotAvatar() {
        val table = activeFromSeats(
            street = BettingRound.Preflop,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                botSeat(index = 1),
            ),
        )
        val bot = table.seats.single { it.index == 1 }
        assertEquals(BotAvatarEmoji, bot.emoji, "every bot reads as a bot at the table")
    }

    @Test
    fun botSeatWithPersonality_stillProjectsRobotAvatar_notPersonalityEmoji() {
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = 0,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                botSeat(index = 1),
            ),
            community = emptyList(),
            street = BettingRound.Preflop,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )
        val table = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = mapOf(1 to BotPersonality.Gina),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
        val bot = table.seats.single { it.index == 1 }
        assertEquals(BotAvatarEmoji, bot.emoji, "bots no longer borrow the personality emoji")
        assertEquals(BotPersonality.Gina, bot.personality, "personality still rides the seat for the profile sheet")
    }

    @Test
    fun emptySeat_isNotBusted() {
        val table = activeFromSeats(
            street = BettingRound.Complete,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.NotDealt, empty = true),
            ),
        )
        val empty = table.seats.single { it.index == 1 }
        assertFalse(empty.isBusted, "Empty seats are not busted players")
    }

    private fun seat(
        index: Int,
        stack: Long,
        participation: HandParticipation,
        empty: Boolean = false,
    ): Seat = Seat(
        index = index,
        playerId = if (empty) null else "p$index",
        displayName = if (empty) "" else "Seat$index",
        stack = stack,
        seatStatus = if (empty) SeatStatus.Empty else SeatStatus.Active,
        handParticipation = participation,
    )

    private fun botSeat(index: Int): Seat = Seat(
        index = index,
        playerId = "bot-$index",
        displayName = "Bot$index",
        stack = 1_000,
        seatStatus = SeatStatus.Active,
        handParticipation = HandParticipation.InHand,
        isBot = true,
    )

    private fun activeFromSeats(
        street: BettingRound,
        seats: List<Seat>,
    ): TableUiState.Active {
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = 0,
            seats = seats,
            community = emptyList(),
            street = street,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )
        return TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
    }
}
