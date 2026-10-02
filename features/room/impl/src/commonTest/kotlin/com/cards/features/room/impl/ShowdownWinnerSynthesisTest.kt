package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandCategory
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.Pot
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hand-result the table falls back to when the transient
 * [GameEvent.HandEnded] never arrives but the snapshot is already
 * [BettingRound.Complete] (MP-26 / MP-25 family).
 *
 * Reported by a player as "a hand won when it shouldn't have": the fallback
 * used to name the winner from [Pot.eligibleSeatIndexes], which at a contested
 * showdown is *every seat that reached the river* — so a two-handed showdown
 * badged both players as winners and split the pot between them regardless of
 * who actually had the better hand. The snapshot carries each in-hand seat's
 * hole cards at `Complete` (GameStateScrub keeps them for the reveal), so the
 * winner is computable and must be computed.
 */
class ShowdownWinnerSynthesisTest {

    private fun cardsOf(spec: String): List<Card> =
        spec.split(" ").filter { it.isNotEmpty() }.map { Card.parse(it) }

    // ---------------------------------------------------- contested showdowns

    @Test
    fun lostHandEnded_contestedShowdown_namesOnlyTheBetterHand() {
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("Ac Ad")), // trip aces
                seat(1, holeCards = cardsOf("3h 4h")), // ace-king high on the board
            ),
            street = BettingRound.Complete,
            community = board,
            pots = listOf(Pot(amount = 200, eligibleSeatIndexes = listOf(0, 1))),
            lastWinners = null, // HandEnded never reached us
        )

        val result = assertNotNull(table.handResult, "a Complete snapshot must still produce a result")
        assertEquals(
            listOf(0),
            result.winners.map { it.seatIndex },
            "trip aces must beat board-high; eligibility is who *could* win, not who did",
        )
        assertEquals(200, result.winners.single().amount, "the single winner takes the whole pot")
        assertEquals(
            HandCategory.ThreeOfAKind,
            result.winners.single().handRank?.category,
            "the synthesized winner carries its evaluated rank so the banner can name the hand",
        )
        assertTrue(result.winners.none { it.byFold }, "a contested showdown is not a fold win")
    }

    @Test
    fun lostHandEnded_threeWayShowdown_namesOnlyTheBestHand() {
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("3h 4h")),
                seat(1, holeCards = cardsOf("Kc Ks")), // trip kings
                seat(2, holeCards = cardsOf("Ac Qd")), // two pair, aces and kings
            ),
            street = BettingRound.Complete,
            community = board,
            pots = listOf(Pot(amount = 300, eligibleSeatIndexes = listOf(0, 1, 2))),
            lastWinners = null,
        )

        val result = assertNotNull(table.handResult)
        assertEquals(listOf(1), result.winners.map { it.seatIndex })
        assertEquals(300, result.winners.single().amount)
    }

    @Test
    fun lostHandEnded_genuineTie_splitsThePot() {
        // Identical hands, different suits: both seats play the board.
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("3h 4h")),
                seat(1, holeCards = cardsOf("3c 4c")),
            ),
            street = BettingRound.Complete,
            community = board,
            pots = listOf(Pot(amount = 200, eligibleSeatIndexes = listOf(0, 1))),
            lastWinners = null,
        )

        val result = assertNotNull(table.handResult)
        assertEquals(listOf(0, 1), result.winners.map { it.seatIndex }.sorted())
        assertEquals(200, result.winners.sumOf { it.amount }, "a split must still pay out the whole pot")
        assertTrue(result.winners.all { it.amount == 100L })
    }

    @Test
    fun lostHandEnded_oddChip_stillPaysTheWholePot() {
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("3h 4h")),
                seat(1, holeCards = cardsOf("3c 4c")),
            ),
            street = BettingRound.Complete,
            community = board,
            pots = listOf(Pot(amount = 201, eligibleSeatIndexes = listOf(0, 1))),
            lastWinners = null,
        )

        val result = assertNotNull(table.handResult)
        assertEquals(201, result.winners.sumOf { it.amount })
    }

    // -------------------------------------------------------- side pots

    @Test
    fun lostHandEnded_sidePot_awardsEachPotToItsOwnBestEligibleHand() {
        // Seat 2 is short and only eligible for the main pot. Seat 2 has the
        // best hand overall, so it takes the main pot while the side pot goes
        // to the better of the two seats actually eligible for it.
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("Kc Ks")), // trip kings
                seat(1, holeCards = cardsOf("3h 4h")), // board high
                seat(2, holeCards = cardsOf("Ac Ad")), // trip aces, short stack
            ),
            street = BettingRound.Complete,
            community = board,
            pots = listOf(
                Pot(amount = 300, eligibleSeatIndexes = listOf(0, 1, 2)),
                Pot(amount = 100, eligibleSeatIndexes = listOf(0, 1)),
            ),
            lastWinners = null,
        )

        val result = assertNotNull(table.handResult)
        val byPot = result.winners.groupBy { it.amount }
        assertEquals(
            setOf(2),
            result.winners.filter { it.amount == 300L }.map { it.seatIndex }.toSet(),
            "main pot to the best hand among all three",
        )
        assertEquals(
            setOf(0),
            result.winners.filter { it.amount == 100L }.map { it.seatIndex }.toSet(),
            "side pot to the best hand among the seats eligible for it",
        )
        assertEquals(2, byPot.size)
        assertEquals(400, result.winners.sumOf { it.amount })
    }

    // -------------------------------------------------------- uncontested

    @Test
    fun lostHandEnded_everyoneFolded_isAFoldWin() {
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("Ac Ad")),
                seat(1, holeCards = cardsOf("3h 4h"), participation = HandParticipation.Folded),
            ),
            street = BettingRound.Complete,
            community = cardsOf("Ah Kd 7c"),
            pots = listOf(Pot(amount = 150, eligibleSeatIndexes = listOf(0))),
            lastWinners = null,
        )

        val result = assertNotNull(table.handResult)
        val win = result.winners.single()
        assertEquals(0, win.seatIndex)
        assertEquals(150, win.amount)
        assertTrue(win.byFold, "an uncontested win is a fold win")
    }

    // -------------------------------------------------------- degraded input

    @Test
    fun lostHandEnded_holeCardsScrubbed_fallsBackToEligibilityRatherThanDeadTable() {
        // A fully scrubbed view can't evaluate anything. The table must still get
        // a banner and a Next Hand path (the MP-26 fix) rather than sitting dead.
        val table = project(
            seats = listOf(seat(0), seat(1)),
            street = BettingRound.Complete,
            community = cardsOf("Ah Kd 7c 2s 9h"),
            pots = listOf(Pot(amount = 200, eligibleSeatIndexes = listOf(0, 1))),
            lastWinners = null,
        )

        val result = assertNotNull(table.handResult, "a scrubbed Complete snapshot still needs a result")
        assertEquals(listOf(0, 1), result.winners.map { it.seatIndex }.sorted())
    }

    @Test
    fun liveHandEnded_alwaysWinsOverSynthesis() {
        val board = cardsOf("Ah Kd 7c 2s 9h")
        val table = project(
            seats = listOf(
                seat(0, holeCards = cardsOf("Ac Ad")),
                seat(1, holeCards = cardsOf("3h 4h")),
            ),
            street = BettingRound.Complete,
            community = board,
            pots = listOf(Pot(amount = 200, eligibleSeatIndexes = listOf(0, 1))),
            // The server is authoritative. Even a result we'd synthesize
            // differently must defer to the event that actually arrived.
            lastWinners = GameEvent.HandEnded(
                sequence = 9,
                winners = listOf(
                    com.dangerfield.cards.libraries.gameplay.HandWinner(
                        seatIndex = 1,
                        amount = 200,
                        handRank = null,
                        byFold = false,
                    ),
                ),
                board = board,
                revealedHoleCards = emptyMap(),
            ),
        )

        val result = assertNotNull(table.handResult)
        assertEquals(listOf(1), result.winners.map { it.seatIndex })
    }

    // ---------------------------------------------------------------- builders

    private fun seat(
        index: Int,
        holeCards: List<Card> = emptyList(),
        participation: HandParticipation = HandParticipation.InHand,
    ): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = if (index == 0) "You" else "Opp$index",
        stack = 1_000,
        seatStatus = SeatStatus.Active,
        handParticipation = participation,
        isBot = index != 0,
        holeCards = holeCards,
    )

    private fun project(
        seats: List<Seat>,
        street: BettingRound = BettingRound.Preflop,
        community: List<Card> = emptyList(),
        pots: List<Pot> = emptyList(),
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
            pots = pots,
        ),
        humanSeatIndex = 0,
        personalitiesBySeat = emptyMap(),
        lastWinners = lastWinners,
        lastActionBySeat = emptyMap(),
    )
}
