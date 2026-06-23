package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exhaustive tests for [WinOddsEngine] — the equity gating extracted from
 * [PlayPokerViewModel]. Every NotApplicable reason is pinned, plus the
 * opponent-counting rules and a compute smoke test.
 */
class WinOddsEngineTest {

    private fun cards(spec: String): List<Card> =
        spec.split(" ").filter { it.isNotEmpty() }.map { Card.parse(it) }

    private fun seat(
        index: Int,
        hole: List<Card> = emptyList(),
        participation: HandParticipation = HandParticipation.InHand,
        stack: Long = 1_000,
    ): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = stack,
        seatStatus = SeatStatus.Active,
        handParticipation = participation,
        holeCards = hole,
    )

    private fun state(seats: List<Seat>, community: List<Card> = emptyList()): GameState = GameState(
        settings = RoomSettings.Default,
        handNumber = 1,
        buttonSeatIndex = 0,
        seats = seats,
        community = community,
        street = BettingRound.Flop,
        currentBetThisStreet = 0,
        lastFullRaiseSize = 0,
        actingSeatIndex = 0,
        deckRemaining = emptyList(),
    )

    // ---------- NotApplicable reasons ----------

    @Test
    fun toolNotEquipped_isNotApplicable_evenWithAValidHand() {
        val s = state(listOf(seat(0, hole = cards("Ah Kh")), seat(1)))
        assertEquals(
            WinOddsEngine.EquityInput.NotApplicable,
            WinOddsEngine.inputFor(s, humanSeatIndex = 0, toolEquipped = false),
        )
    }

    @Test
    fun humanNotSeated_isNotApplicable() {
        val s = state(listOf(seat(0, hole = cards("Ah Kh")), seat(1)))
        assertEquals(
            WinOddsEngine.EquityInput.NotApplicable,
            WinOddsEngine.inputFor(s, humanSeatIndex = 99, toolEquipped = true),
        )
    }

    @Test
    fun humanWithoutTwoHoleCards_isNotApplicable() {
        val noCards = state(listOf(seat(0, hole = emptyList()), seat(1)))
        assertEquals(
            WinOddsEngine.EquityInput.NotApplicable,
            WinOddsEngine.inputFor(noCards, humanSeatIndex = 0, toolEquipped = true),
        )
        val oneCard = state(listOf(seat(0, hole = cards("Ah")), seat(1)))
        assertEquals(
            WinOddsEngine.EquityInput.NotApplicable,
            WinOddsEngine.inputFor(oneCard, humanSeatIndex = 0, toolEquipped = true),
        )
    }

    @Test
    fun noOpponentsLeftInHand_isNotApplicable() {
        val s = state(
            listOf(
                seat(0, hole = cards("Ah Kh")),
                seat(1, participation = HandParticipation.Folded),
                seat(2, participation = HandParticipation.NotDealt),
            ),
        )
        assertEquals(
            WinOddsEngine.EquityInput.NotApplicable,
            WinOddsEngine.inputFor(s, humanSeatIndex = 0, toolEquipped = true),
        )
    }

    // ---------- Compute ----------

    @Test
    fun validHand_producesComputeWithHoleBoardAndOpponentCount() {
        val board = cards("Ah 7c 2d")
        val s = state(
            seats = listOf(seat(0, hole = cards("As Ks")), seat(1)),
            community = board,
        )
        val input = WinOddsEngine.inputFor(s, humanSeatIndex = 0, toolEquipped = true)
        val compute = assertIs<WinOddsEngine.EquityInput.Compute>(input)
        assertEquals(cards("As Ks"), compute.hole)
        assertEquals(board, compute.community)
        assertEquals(1, compute.opponents)
    }

    @Test
    fun opponentCount_includesInHandAndAllIn_excludesFoldedNotDealtAndSelf() {
        val s = state(
            listOf(
                seat(0, hole = cards("As Ks")), // human (excluded)
                seat(1, participation = HandParticipation.InHand), // counted
                seat(2, participation = HandParticipation.AllIn, stack = 0), // counted
                seat(3, participation = HandParticipation.Folded), // excluded
                seat(4, participation = HandParticipation.NotDealt), // excluded
            ),
        )
        val compute = assertIs<WinOddsEngine.EquityInput.Compute>(
            WinOddsEngine.inputFor(s, humanSeatIndex = 0, toolEquipped = true),
        )
        assertEquals(2, compute.opponents)
    }

    @Test
    fun compute_returnsBreakdownSummingToWholePercent() {
        val breakdown = WinOddsEngine.compute(
            WinOddsEngine.EquityInput.Compute(
                hole = cards("As Ah"),
                community = emptyList(),
                opponents = 1,
            ),
            iterations = 200,
        )
        assertEquals(100, breakdown.winPct + breakdown.tiePct + breakdown.losePct)
        assertTrue(breakdown.winPct in 0..100)
        assertTrue(
            breakdown.winPct > breakdown.losePct,
            "pocket aces heads-up should win far more than it loses (got $breakdown)",
        )
    }
}
