package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure projection edges of [TableUiState.fromGameState] not covered by
 * [TableUiStateTest]: hole-card hide/reveal, the [TableUiState.Active.humanHandLabel]
 * preview, blind-seat resolution, and the showdown [HandResultView]. No VM.
 */
class TableProjectionEdgeTest {

    private fun cardsOf(spec: String): List<Card> =
        spec.split(" ").filter { it.isNotEmpty() }.map { Card.parse(it) }

    // ---------- hole-card hide / reveal ----------

    @Test
    fun humanSeatAlwaysSeesOwnHoleCards() {
        val table = project(
            seats = listOf(
                human(holeCards = cardsOf("Ah Kh")),
                opponent(holeCards = cardsOf("Qs Qd")),
            ),
        )
        assertEquals(2, table.seats.single { it.isHuman }.holeCards.size)
    }

    @Test
    fun opponentMidHand_isHidden_andShowsCardBacks() {
        val table = project(
            seats = listOf(
                human(holeCards = cardsOf("Ah Kh")),
                opponent(holeCards = cardsOf("Qs Qd")),
            ),
            lastWinners = null,
        )
        val opp = table.seats.single { it.index == 1 }
        assertTrue(opp.holeCards.isEmpty(), "opponent cards hidden mid-hand")
        assertTrue(opp.showHoleCardBacks, "an in-hand hidden opponent shows card backs")
    }

    @Test
    fun opponentAtShowdown_revealsCards_noBacks() {
        val table = project(
            seats = listOf(
                human(holeCards = cardsOf("Ah Kh")),
                opponent(holeCards = cardsOf("Qs Qd")),
            ),
            street = BettingRound.Complete,
            lastWinners = handEnded(
                winners = listOf(winner(seat = 0)),
                revealed = mapOf(1 to cardsOf("Qs Qd")),
            ),
        )
        val opp = table.seats.single { it.index == 1 }
        assertEquals(cardsOf("Qs Qd"), opp.holeCards, "revealed at showdown")
        assertFalse(opp.showHoleCardBacks)
    }

    @Test
    fun foldedOpponent_showsNoCards_andNoBacks() {
        val table = project(
            seats = listOf(
                human(holeCards = cardsOf("Ah Kh")),
                opponent(holeCards = cardsOf("Qs Qd"), participation = HandParticipation.Folded),
            ),
        )
        val opp = table.seats.single { it.index == 1 }
        assertTrue(opp.holeCards.isEmpty())
        assertFalse(opp.showHoleCardBacks, "a folded seat shows nothing, not backs")
    }

    // ---------- humanHandLabel preview ----------

    @Test
    fun preflopPocketPair_labelsPocketPair() {
        val table = project(seats = listOf(human(holeCards = cardsOf("Ah Ad")), opponent()))
        assertEquals("Pocket pair", table.humanHandLabel)
    }

    @Test
    fun preflopNonPair_hasNoLabel() {
        val table = project(seats = listOf(human(holeCards = cardsOf("Ah Kd")), opponent()))
        assertNull(table.humanHandLabel)
    }

    @Test
    fun madePairOnFlop_labelsByCategory() {
        val table = project(
            seats = listOf(human(holeCards = cardsOf("Ah Kd")), opponent()),
            street = BettingRound.Flop,
            community = cardsOf("Ac 7c 2s"),
        )
        assertEquals("Pair", table.humanHandLabel)
    }

    // ---------- blind seat resolution ----------

    @Test
    fun headsUp_buttonIsSmallBlind() {
        val table = project(
            seats = listOf(human(), opponent()),
            buttonSeatIndex = 0,
        )
        assertEquals(0, table.smallBlindSeatIndex, "heads-up: the button posts the small blind")
        assertEquals(1, table.bigBlindSeatIndex)
    }

    @Test
    fun threeHanded_blindsAreLeftOfButton() {
        val table = project(
            seats = listOf(human(), opponent(index = 1), opponent(index = 2)),
            buttonSeatIndex = 0,
        )
        assertEquals(1, table.smallBlindSeatIndex, "SB is left of the button")
        assertEquals(2, table.bigBlindSeatIndex, "BB is left of the SB")
    }

    // ---------- hand-result dialog ----------

    @Test
    fun midHand_noHandResult() {
        val table = project(seats = listOf(human(), opponent()), lastWinners = null)
        assertNull(table.handResult)
    }

    @Test
    fun showdown_handResultCarriesWinnersAndBoard() {
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(human(), opponent()),
            street = BettingRound.Complete,
            community = board,
            lastWinners = handEnded(winners = listOf(winner(seat = 0)), board = board),
        )
        val result = assertNotNull(table.handResult)
        assertEquals(board, result.board)
        assertTrue(result.winners.any { it.seatIndex == 0 })
    }

    // ---------- builders ----------

    private fun human(
        holeCards: List<Card> = emptyList(),
        participation: HandParticipation = HandParticipation.InHand,
    ): Seat = Seat(
        index = 0,
        playerId = "human",
        displayName = "You",
        stack = 1_000,
        seatStatus = SeatStatus.Active,
        handParticipation = participation,
        isBot = false,
        holeCards = holeCards,
    )

    private fun opponent(
        index: Int = 1,
        holeCards: List<Card> = emptyList(),
        participation: HandParticipation = HandParticipation.InHand,
    ): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = "Opp$index",
        stack = 1_000,
        seatStatus = SeatStatus.Active,
        handParticipation = participation,
        isBot = true,
        holeCards = holeCards,
    )

    private fun winner(seat: Int) = HandWinner(seatIndex = seat, amount = 100, handRank = null, byFold = false)

    private fun handEnded(
        winners: List<HandWinner>,
        board: List<Card> = emptyList(),
        revealed: Map<Int, List<Card>> = emptyMap(),
    ) = GameEvent.HandEnded(sequence = 9, winners = winners, board = board, revealedHoleCards = revealed)

    private fun project(
        seats: List<Seat>,
        street: BettingRound = BettingRound.Preflop,
        community: List<Card> = emptyList(),
        buttonSeatIndex: Int = 0,
        lastWinners: GameEvent.HandEnded? = null,
    ): TableUiState.Active = TableUiState.fromGameState(
        gameState = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = buttonSeatIndex,
            seats = seats,
            community = community,
            street = street,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        ),
        humanSeatIndex = 0,
        personalitiesBySeat = emptyMap(),
        lastWinners = lastWinners,
        lastActionBySeat = emptyMap(),
    )
}
