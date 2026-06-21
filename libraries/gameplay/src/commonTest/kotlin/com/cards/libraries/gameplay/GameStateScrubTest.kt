package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameStateScrubTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val cardsA = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades))
    private val cardsB = listOf(Card(Rank.Queen, Suit.Hearts), Card(Rank.Jack, Suit.Hearts))
    private val cardsC = listOf(Card(Rank.Ten, Suit.Clubs), Card(Rank.Nine, Suit.Diamonds))

    private fun seat(
        index: Int,
        cards: List<Card>,
        participation: HandParticipation = HandParticipation.InHand,
    ) = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = 1_000,
        seatStatus = SeatStatus.Active,
        handParticipation = participation,
        holeCards = cards,
    )

    private fun state(
        street: BettingRound,
        seats: List<Seat>,
    ) = GameState(
        settings = settings,
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

    @Test
    fun preflop_viewerSeesOwn_othersScrubbed() {
        val s = state(BettingRound.Preflop, listOf(seat(0, cardsA), seat(1, cardsB), seat(2, cardsC)))

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        assertEquals(cardsA, scrubbed.seats[0].holeCards)
        assertTrue(scrubbed.seats[1].holeCards.isEmpty())
        assertTrue(scrubbed.seats[2].holeCards.isEmpty())
    }

    @Test
    fun river_stillScrubsOthers() {
        // Pre-showdown betting rounds all behave the same — privacy holds.
        val s = state(BettingRound.River, listOf(seat(0, cardsA), seat(1, cardsB)))

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 1)

        assertTrue(scrubbed.seats[0].holeCards.isEmpty())
        assertEquals(cardsB, scrubbed.seats[1].holeCards)
    }

    @Test
    fun showdown_inHandSeats_areRevealed() {
        val s = state(
            BettingRound.Showdown,
            listOf(
                seat(0, cardsA, participation = HandParticipation.InHand),
                seat(1, cardsB, participation = HandParticipation.AllIn),
                seat(2, cardsC, participation = HandParticipation.InHand),
            ),
        )

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        // All three went to showdown — viewer sees everyone's cards.
        assertEquals(cardsA, scrubbed.seats[0].holeCards)
        assertEquals(cardsB, scrubbed.seats[1].holeCards)
        assertEquals(cardsC, scrubbed.seats[2].holeCards)
    }

    @Test
    fun showdown_foldedSeat_staysScrubbed() {
        val s = state(
            BettingRound.Showdown,
            listOf(
                seat(0, cardsA, participation = HandParticipation.InHand),
                seat(1, cardsB, participation = HandParticipation.Folded),
                seat(2, cardsC, participation = HandParticipation.AllIn),
            ),
        )

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        // Seat 1 mucked — even at showdown nobody sees their hand.
        assertEquals(cardsA, scrubbed.seats[0].holeCards)
        assertTrue(scrubbed.seats[1].holeCards.isEmpty())
        assertEquals(cardsC, scrubbed.seats[2].holeCards)
    }

    @Test
    fun complete_behavesLikeShowdown() {
        // After showdown resolves, BettingRound becomes Complete. Cards
        // should remain visible for the post-hand summary UI.
        val s = state(
            BettingRound.Complete,
            listOf(
                seat(0, cardsA, participation = HandParticipation.InHand),
                seat(1, cardsB, participation = HandParticipation.AllIn),
            ),
        )

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        assertEquals(cardsA, scrubbed.seats[0].holeCards)
        assertEquals(cardsB, scrubbed.seats[1].holeCards)
    }

    @Test
    fun viewerNotSeated_everyoneScrubbed() {
        val s = state(BettingRound.Preflop, listOf(seat(0, cardsA), seat(1, cardsB)))

        val scrubbed = s.scrubbedFor(viewerSeatIndex = -1)

        assertTrue(scrubbed.seats[0].holeCards.isEmpty())
        assertTrue(scrubbed.seats[1].holeCards.isEmpty())
    }

    @Test
    fun seatWithEmptyHoleCards_unchanged() {
        // Some seats legitimately have empty holeCards mid-hand (Empty
        // seat, dealer rotation, etc.). Scrub must not introduce
        // unnecessary allocations.
        val emptyCardsSeat = seat(1, cards = emptyList(), participation = HandParticipation.NotDealt)
        val s = state(BettingRound.Preflop, listOf(seat(0, cardsA), emptyCardsSeat))

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        // Reference equality — the seat object wasn't copied.
        assertTrue(scrubbed.seats[1] === emptyCardsSeat)
    }

    @Test
    fun viewerOwnSeat_alwaysReturnedUnchanged() {
        // Even at Complete state, the viewer should see their own cards
        // without going through the "showdown" branch (which would still
        // be correct but adds no value to the assertion).
        val viewer = seat(0, cardsA, participation = HandParticipation.Folded)
        val s = state(BettingRound.Showdown, listOf(viewer, seat(1, cardsB)))

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        // Viewer folded but still sees their own muck.
        assertEquals(cardsA, scrubbed.seats[0].holeCards)
    }

    @Test
    fun hiddenBot_isScrubbedToLookHuman_butRevealedBotKeepsItsMarker() {
        val revealedBot = seat(1, cardsB).copy(isBot = true, botStyleKey = "Jane")
        val hiddenBot = seat(2, cardsC).copy(isBot = true, botStyleKey = null)
        val s = state(BettingRound.Preflop, listOf(seat(0, cardsA), revealedBot, hiddenBot))

        val scrubbed = s.scrubbedFor(viewerSeatIndex = 0)

        assertTrue(scrubbed.seats[1].isBot, "a revealed bot keeps its bot marker on the wire")
        assertEquals("Jane", scrubbed.seats[1].botStyleKey)
        assertTrue(!scrubbed.seats[2].isBot, "a hidden bot is scrubbed to present as a human")
    }
}
