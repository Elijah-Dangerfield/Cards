package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.BotAvatarEmoji
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.deterministicDeck
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
    fun headsUpWithButtonOnSatOutSeat_blindBadgesMatchTheSeatsTheEngineCharged() {
        val seats = listOf(
            seat(index = 0, stack = 0, participation = HandParticipation.NotDealt),
            seat(index = 1, stack = 1_000, participation = HandParticipation.InHand),
            seat(index = 2, stack = 1_000, participation = HandParticipation.InHand),
        )
        val dealt = GameEngine.startHand(
            settings = RoomSettings.Default,
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(seed = 1),
        )
        val posted = dealt.events.filterIsInstance<GameEvent.BlindPosted>()
        val chargedSmall = posted.single { it.isSmall }.seatIndex
        val chargedBig = posted.single { !it.isSmall }.seatIndex

        val table = TableUiState.fromGameState(
            gameState = dealt.state,
            humanSeatIndex = 1,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )

        assertEquals(
            chargedSmall,
            table.smallBlindSeatIndex,
            "Small-blind badge must sit on the seat the engine actually charged",
        )
        assertEquals(
            chargedBig,
            table.bigBlindSeatIndex,
            "Big-blind badge must sit on the seat the engine actually charged",
        )
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
    fun turnTimer_defaultsOff_andCarriesSequence() {
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = 0,
            seats = listOf(seat(index = 0, stack = 1_000, participation = HandParticipation.InHand)),
            community = emptyList(),
            street = BettingRound.Preflop,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = 0,
            deckRemaining = emptyList(),
            lastSequence = 7,
        )
        val solo = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
        assertEquals(null, solo.turnTimerSeconds, "solo tables don't enforce a per-turn timer")
        assertEquals(7L, solo.turnSequence, "turn sequence rides through from the game state")
    }

    @Test
    fun seatView_carriesContributedThisHand_forLeaveSettleForfeitNote() {
        // ROOM-4: the leave-confirm dialog reads the human seat's hand-level
        // contribution to call out a posted blind / committed chips forfeited by
        // leaving mid-hand. The projection must surface it, not just the
        // per-street figure.
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = 0,
            seats = listOf(
                seat(
                    index = 0,
                    stack = 950,
                    participation = HandParticipation.InHand,
                    contributedThisHand = 50,
                ),
            ),
            community = emptyList(),
            street = BettingRound.Flop,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = 0,
            deckRemaining = emptyList(),
        )
        val table = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
        assertEquals(
            50L,
            table.seats.single { it.isHuman }.contributedThisHand,
            "hand-level contribution rides onto the seat view for the leave-settle forfeit note",
        )
    }

    @Test
    fun turnTimer_surfacesSettingValue_whenEnforced() {
        val state = GameState(
            settings = RoomSettings.Default.copy(turnTimerSeconds = 45),
            handNumber = 1,
            buttonSeatIndex = 0,
            seats = listOf(seat(index = 0, stack = 1_000, participation = HandParticipation.InHand)),
            community = emptyList(),
            street = BettingRound.Preflop,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = 0,
            deckRemaining = emptyList(),
        )
        val mp = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
            turnTimerEnforced = true,
        )
        assertEquals(45, mp.turnTimerSeconds, "enforced tables surface the configured timeout")
    }

    @Test
    fun completeSnapshot_revealsInHandOpponentCards_evenWithoutHandEndedEvent() {
        // MP-25: a showdown hand the server already resolved to Complete carries
        // the in-hand opponents' hole cards on the snapshot (GameStateScrub keeps
        // them visible at Complete). The reveal must not depend on the transient
        // HandEnded event also arriving — if it's lost / rolled out of the event
        // replay / raced by the next hand, the snapshot alone must still show the
        // showdown. lastWinners = null simulates "event never reached the client."
        val holeCards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.Ace, Suit.Diamonds))
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 4,
            buttonSeatIndex = 0,
            seats = listOf(
                seatWithHole(index = 0, participation = HandParticipation.InHand, holeCards = emptyList()),
                seatWithHole(index = 1, participation = HandParticipation.InHand, holeCards = holeCards),
            ),
            community = emptyList(),
            street = BettingRound.Complete,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )
        val table = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
        val opponent = table.seats.single { it.index == 1 }
        assertEquals(
            holeCards,
            opponent.holeCards,
            "an in-hand opponent's cards on a Complete snapshot are revealed without the HandEnded event",
        )
        assertFalse(opponent.showHoleCardBacks, "revealed cards are face-up, not backs")
    }

    @Test
    fun completeSnapshot_keepsFoldedOpponentMucked() {
        // The flip side: a seat that folded earlier mucked its cards — the server
        // scrubs them to empty before broadcast — so even at Complete there's
        // nothing to reveal and the seat shows backs/empty, never face-up.
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 4,
            buttonSeatIndex = 0,
            seats = listOf(
                seatWithHole(index = 0, participation = HandParticipation.InHand, holeCards = emptyList()),
                seatWithHole(index = 1, participation = HandParticipation.Folded, holeCards = emptyList()),
            ),
            community = emptyList(),
            street = BettingRound.Complete,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )
        val table = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
        val folded = table.seats.single { it.index == 1 }
        assertTrue(folded.holeCards.isEmpty(), "a mucked folder reveals nothing at showdown")
    }

    @Test
    fun midHandSnapshot_stillHidesOpponentCards() {
        // The reveal is gated on Complete — mid-hand (Flop here) an opponent's
        // cards stay hidden even though the seat is in the hand, so the new
        // Complete-reveal path can't leak cards during live play.
        val holeCards = listOf(Card(Rank.King, Suit.Spades), Card(Rank.King, Suit.Diamonds))
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 4,
            buttonSeatIndex = 0,
            seats = listOf(
                seatWithHole(index = 0, participation = HandParticipation.InHand, holeCards = emptyList()),
                seatWithHole(index = 1, participation = HandParticipation.InHand, holeCards = holeCards),
            ),
            community = emptyList(),
            street = BettingRound.Flop,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = 1,
            deckRemaining = emptyList(),
        )
        val table = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
        val opponent = table.seats.single { it.index == 1 }
        assertTrue(opponent.holeCards.isEmpty(), "mid-hand an opponent's cards stay hidden")
        assertTrue(opponent.showHoleCardBacks, "mid-hand an in-hand opponent shows card backs")
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
        contributedThisHand: Long = 0,
    ): Seat = Seat(
        index = index,
        playerId = if (empty) null else "p$index",
        displayName = if (empty) "" else "Seat$index",
        stack = stack,
        seatStatus = if (empty) SeatStatus.Empty else SeatStatus.Active,
        handParticipation = participation,
        contributedThisHand = contributedThisHand,
    )

    private fun seatWithHole(
        index: Int,
        participation: HandParticipation,
        holeCards: List<Card>,
    ): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = "Seat$index",
        stack = 1_000,
        seatStatus = SeatStatus.Active,
        handParticipation = participation,
        holeCards = holeCards,
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
        buttonSeatIndex: Int = 0,
    ): TableUiState.Active {
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = buttonSeatIndex,
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
