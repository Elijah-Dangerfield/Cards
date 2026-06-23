package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.usecase.HandResultSummaryBuilder

import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins the table-composition credit downgrade in [HandResultSummaryBuilder]
 * (product-spec.md §5.4). The builder is the single place a finished hand
 * becomes the mode-tagged [com.dangerfield.cards.libraries.cards.HandResultSummary]
 * that XP + achievement attribution read, so the downgrade is enforced here:
 * a multiplayer hand on a bot-stacked / solo table is credited at the BOTS
 * (practice) tier, while a majority-human table keeps MULTIPLAYER and a
 * genuine solo session is never touched.
 *
 * NOT covered here: the eligibility rule itself (`MultiplayerCreditTest`) or
 * the per-field hand translation (exercised via the engine in
 * `PlayPokerViewModelIntegrationTest`).
 */
class HandResultSummaryBuilderTest {

    private val handEnded = GameEvent.HandEnded(
        sequence = 0,
        winners = emptyList(),
        board = emptyList(),
        revealedHoleCards = emptyMap(),
    )

    @Test
    fun multiplayer_majorityHuman_keepsMultiplayerCredit() {
        val summary = HandResultSummaryBuilder.build(
            event = handEnded,
            state = table(humans = 2, bots = 2),
            humanSeatIndex = 0,
            mode = XpMode.MULTIPLAYER,
        )
        assertEquals(XpMode.MULTIPLAYER, summary.mode)
    }

    @Test
    fun multiplayer_botStacked_downgradesToPracticeCredit() {
        val summary = HandResultSummaryBuilder.build(
            event = handEnded,
            state = table(humans = 2, bots = 4),
            humanSeatIndex = 0,
            mode = XpMode.MULTIPLAYER,
        )
        assertEquals(XpMode.BOTS, summary.mode)
    }

    @Test
    fun multiplayer_soloRouted_downgradesToPracticeCredit() {
        val summary = HandResultSummaryBuilder.build(
            event = handEnded,
            state = table(humans = 1, bots = 3),
            humanSeatIndex = 0,
            mode = XpMode.MULTIPLAYER,
        )
        assertEquals(XpMode.BOTS, summary.mode)
    }

    @Test
    fun solo_botSession_modeUntouched() {
        // A real solo session declares BOTS and never qualifies (1 human),
        // but the downgrade is a no-op — the guard only fires for MULTIPLAYER.
        val summary = HandResultSummaryBuilder.build(
            event = handEnded,
            state = table(humans = 1, bots = 3),
            humanSeatIndex = 0,
            mode = XpMode.BOTS,
        )
        assertEquals(XpMode.BOTS, summary.mode)
    }

    @Test
    fun foldedHuman_noShowdownCredit_evenWhenCardsAreRevealed() {
        // The human folded a hand whose board would have made a straight.
        // Even if the reveal map carries their cards (the MP showdown reveal
        // path can over-share), a folded seat must never count as having
        // shown a hand — reachedShowdown / handCategory stay false/null so no
        // SHOW_* achievement credits.
        val humanHole = listOf(Card(Rank.Nine, Suit.Hearts), Card(Rank.King, Suit.Diamonds))
        val opponentHole = listOf(Card(Rank.Ace, Suit.Clubs), Card(Rank.Ace, Suit.Spades))
        val board = listOf(
            Card(Rank.Five, Suit.Hearts),
            Card(Rank.Six, Suit.Clubs),
            Card(Rank.Seven, Suit.Diamonds),
            Card(Rank.Eight, Suit.Spades),
            Card(Rank.Two, Suit.Clubs),
        )
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, isBot = false, playerId = "human")
                    .copy(handParticipation = HandParticipation.Folded, holeCards = humanHole),
                testSeat(index = 1, isBot = false, playerId = "peer")
                    .copy(holeCards = opponentHole),
            ),
        )
        val event = GameEvent.HandEnded(
            sequence = 0,
            winners = listOf(HandWinner(seatIndex = 1, amount = 100L, handRank = null, byFold = false)),
            board = board,
            revealedHoleCards = mapOf(0 to humanHole, 1 to opponentHole),
        )

        val summary = HandResultSummaryBuilder.build(
            event = event,
            state = state,
            humanSeatIndex = 0,
            mode = XpMode.MULTIPLAYER,
        )

        assertEquals(true, summary.wasFold)
        assertFalse(summary.reachedShowdown)
        assertNull(summary.handCategory)
        assertFalse(summary.wonPot)
    }

    private fun table(humans: Int, bots: Int) = stubGameState(
        seats = buildList {
            repeat(humans) { i -> add(testSeat(index = size, isBot = false, playerId = "human-$i")) }
            repeat(bots) { i -> add(testSeat(index = size, isBot = true, playerId = "bot-$i")) }
        },
    )
}
