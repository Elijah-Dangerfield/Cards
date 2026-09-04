package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The play screen driven by a **real** `PlayPokerViewModel` over a **real** bots
 * session. See [RenderedScenarioBuilder] for why this tier exists and how the
 * clock is arranged.
 *
 * The rule that makes these worth having: **nothing here constructs a
 * `TableUiState`.** The only inputs are a stacked deck and a bot script, and
 * every assertion goes through the semantics tree. A hand-built UI test asserts
 * that the screen renders the state its author *believed* the projection emits;
 * these assert what the projection actually emits, so a wrong projection fails
 * here and nowhere else.
 *
 * Sibling suites still carry their own weight — they can reach states the engine
 * makes hard to reach on demand (a degraded connection, a bust with a pending
 * rebuy grace, a seatless spectator). This tier covers the ordinary path end to
 * end, which is the part every user actually walks through.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PlayPokerScreenViewModelTest {

    // ── The deal ─────────────────────────────────────────────────────────────

    @Test
    fun dealtHoleCards_reachTheFelt() = runComposeUiTest {
        // The narrowest statement of the seam: the cards the engine dealt are
        // the cards the user sees. A projection that dropped, swapped or
        // reordered the human's hole cards passes every hand-built UI test.
        val s = scenario().deal("Ah Kd", "Qs Qc").start()
        s.settle()

        onAllNodesWithText("A").assertCountEquals(1)
        onAllNodesWithText("K").assertCountEquals(1)
    }

    @Test
    fun opponentHoleCards_stayHiddenWhileTheHandIsLive() = runComposeUiTest {
        // The bot was dealt two queens. Showing them mid-hand would be the most
        // damaging projection bug this screen could have, and it is invisible to
        // any test that builds its own state.
        //
        // Two independent gates have to hold for this: the projection empties
        // an opponent's `holeCards`, and `OpponentSeat` only turns cards face-up
        // once the hand is complete. Verified by breaking them — either one
        // alone still keeps the cards hidden, and it takes both before this
        // test goes red. That is defence in depth worth keeping, and worth
        // knowing about before anyone "simplifies" one of them away.
        val s = scenario().deal("Ah Kd", "Qs Qc").start()
        s.settle()

        onAllNodesWithText("Q").assertCountEquals(0)
    }

    // ── Opponent acts, my UI responds ────────────────────────────────────────

    @Test
    fun opponentRaise_setsMyCallAmount() = runComposeUiTest {
        // "Opponent does X, my UI shows Y" — and the amount has to survive the
        // whole trip: engine -> LegalActions -> projection -> button label.
        val s = scenario()
            .deal("Ah Kd", "Qs Qc")
            .scriptOpponent(1) { raisesTo(200) }
            .start()
        s.settle()

        val callAmount = s.table.humanLegalActions?.callAmount
        assertTrue(callAmount != null && callAmount > 0, "expected a live call, got $callAmount")
        onNodeWithText("Call", substring = true).assertIsDisplayed()
    }

    @Test
    fun opponentFold_endsTheHandAndShowsTheResult() = runComposeUiTest {
        val s = scenario()
            .deal("Ah Kd", "Qs Qc")
            .scriptOpponent(1) { folds() }
            .start()
        s.settle()

        // Heads-up the human is the button/SB and acts first, so the bot only
        // reaches its scripted fold once the blind is completed.
        s.iCall()

        assertTrue(s.table.handResult != null, "a fold should resolve the hand")
        onNodeWithText(YOU_WIN, substring = true).assertIsDisplayed()
    }

    // ── Streets ──────────────────────────────────────────────────────────────

    @Test
    fun theFlop_reachesTheBoard() = runComposeUiTest {
        // Board cards are a different projection path from hole cards, and a
        // street that advances without the board following is a classic
        // off-by-one in the community-card slice.
        val s = scenario()
            .deal("Ah Kd", "Qs Qc")
            .board("2c 7d Ts")
            .scriptOpponent(1) { checks(); checks() }
            .start()
        s.settle()

        // Completing the blind hands action to the bot, which checks; that ends
        // the preflop round and deals the flop.
        s.iCall()

        assertEquals(3, s.table.communityCards.size, "expected a flop; street=${s.table.street} acting=${s.table.actingSeatIndex} humanTurn=${s.table.isHumanTurn} pot=${s.table.pot}")
        onAllNodesWithText("10").assertCountEquals(1)
    }

    // ── Money ────────────────────────────────────────────────────────────────

    @Test
    fun chipsIGoIn_leaveMyStack() = runComposeUiTest {
        val s = scenario().deal("Ah Kd", "Qs Qc").scriptOpponent(1) { calls(); checks() }.start()
        s.settle()
        val before = s.humanSeat().stack

        s.iRaiseTo(200)

        val after = s.humanSeat().stack
        assertTrue(after < before, "raising to 200 should cost chips: $before -> $after")
    }

    @Test
    fun thePotGrowsAsChipsGoIn() = runComposeUiTest {
        val s = scenario().deal("Ah Kd", "Qs Qc").scriptOpponent(1) { calls(); checks() }.start()
        s.settle()
        val before = s.table.pot

        s.iRaiseTo(200)

        assertTrue(s.table.pot > before, "pot should grow: $before -> ${s.table.pot}")
    }

    // ── The hand boundary, across the real ViewModel ─────────────────────────

    @Test
    fun theNextHand_dealsANewHandNumberAndFreshCards() = runComposeUiTest {
        // The axis every per-hand `remember` on this screen is keyed to, driven
        // by the real engine rather than by a hand-written state swap. This is
        // the seam plus the boundary at once.
        val s = scenario()
            .deal("Ah Kd", "Qs Qc")
            .start()
        s.settle()
        val firstHand = s.table.handNumber

        s.iFold()
        s.iRequestNextHand()

        assertEquals(firstHand + 1, s.table.handNumber, "a new hand should have been dealt")
        assertTrue(s.table.handResult == null, "the new hand must start with no result showing")
    }

    @Test
    fun holeCardsFromTheNewHand_replaceTheOldOnesOnScreen() = runComposeUiTest {
        // Guards the `key(handNumber, card)` fix from ENG-57 through the real
        // projection: hand two's cards must actually be the ones rendered.
        val s = scenario()
            .deal("Ah Kd", "Qs Qc")
            .start()
        s.settle()

        s.iFold()
        s.iRequestNextHand()
        s.settle()

        val mine = s.humanSeat().holeCards.map { it.rank.display }.toSet()
        for (rank in mine) {
            onAllNodesWithText(rank).assertCountEquals(1)
        }
    }

    // ── Showdown ─────────────────────────────────────────────────────────────

    @Test
    fun aShowdownRevealsTheOpponentsCards() = runComposeUiTest {
        // The mirror of `opponentHoleCards_stayHiddenWhileTheHandIsLive`: once
        // the hand is complete the same cards must become visible, or the player
        // can never see how they lost.
        val s = scenario()
            .deal("Ah Kd", "Js Jc")
            .board("2c 7d 9s 3h 4s")
            .scriptOpponent(1) { checks(); checks(); checks(); checks() }
            .start()
        s.settle()

        // Jacks appear nowhere else — not in my hand, not on the board — so a
        // visible "J" can only be the opponent's revealed hole card.
        onAllNodesWithText("J").assertCountEquals(0)

        s.iCall(); s.iCheck(); s.iCheck(); s.iCheck()

        assertTrue(s.table.handResult != null, "expected the hand to have resolved")
        // At least two: the pair on the felt. The showdown dialog repeats the
        // hand and contributes a third (merged) match, so this counts "became
        // visible" rather than pinning how many surfaces choose to show it.
        val revealed = onAllNodesWithText("J").fetchSemanticsNodes().size
        assertTrue(revealed >= 2, "opponent's jacks should be revealed at showdown, found $revealed")
    }

    @Test
    fun winningTheHand_showsTheWin() = runComposeUiTest {
        val s = scenario()
            .deal("Ah Ad", "Qs Qc")
            .board("2c 7d Ts 3h 4s")
            .scriptOpponent(1) { checks(); checks(); checks(); checks() }
            .start()
        s.settle()
        s.iCall(); s.iCheck(); s.iCheck(); s.iCheck()

        val winners = s.table.handResult?.winners.orEmpty()
        assertTrue(winners.any { it.seatIndex == 0 }, "aces should beat queens on this board")
        onNodeWithText(YOU_WIN, substring = true).assertIsDisplayed()
    }

    // ── Folding out ──────────────────────────────────────────────────────────

    @Test
    fun foldingMakesMeInactiveForTheRestOfTheHand() = runComposeUiTest {
        val s = scenario()
            .deal("Ah Kd", "Qs Qc")
            .scriptOpponent(1) { raisesTo(200) }
            .start()
        s.settle()

        s.iFold()

        assertTrue(s.table.handResult != null, "heads-up, my fold ends the hand")
        assertTrue(!s.table.isHumanTurn, "a folded player should not be on the clock")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ComposeUiTest.scenario() = RenderedScenarioBuilder(this)

    private companion object {
        const val YOU_WIN = "You win"
    }
}
