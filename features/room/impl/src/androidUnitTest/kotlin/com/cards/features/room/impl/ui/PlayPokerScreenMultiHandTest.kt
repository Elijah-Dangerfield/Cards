package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.LegalActions
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * Compose tests that **cross a hand boundary**.
 *
 * This is the coverage shape [PlayPokerScreenTest] is missing, and the gap is
 * not academic. Those 14 tests each render one state and were green throughout
 * the week that tap-to-flip silently stopped working on the second hand of every
 * session. The bug needed two hands to observe: `manuallyFacedown` was
 * `remember(human.holeCards) { mutableStateOf(false) }` — a fresh `MutableState`
 * per hand — while the tap handler writing it is `pointerInput(Unit)`, which is
 * never restarted. From hand two on, every tap wrote to an orphaned object.
 *
 * `handNumber` and `holeCards` are the axes that re-arm every per-hand
 * `remember` on this screen. Anything keyed on them, and anything capturing
 * something keyed on them, is only exercised by advancing a hand — so these
 * tests advance one.
 *
 * State is hoisted rather than driven through a real session on purpose. The
 * bug class here lives in the *binding* between state and UI, and a
 * `mutableStateOf(PlayPokerState)` reproduces it exactly while staying
 * deterministic and fast. Replaying real gameplay belongs in `:apps:integration`.
 *
 * **Asserting face-up vs face-down needs no test tags.** `PlayingCard` emits the
 * rank and suit as real `Text` nodes; `PlayingCardBack` is Canvas-drawn with no
 * text. So the presence of the rank glyph *is* the assertion.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class PlayPokerScreenMultiHandTest {

    /**
     * The regression guard for the tap-to-flip bug. Must fail against
     * `82e57c4f~1`.
     */
    @Test
    fun tapToFlip_stillTogglesOnTheSecondHand() = runComposeUiTest {
        val screen = renderAcrossHands(handOne())

        screen.assertFaceUp(HAND_ONE_RANK)
        screen.tapHoleCards(HAND_ONE_RANK)
        screen.assertFaceDown(HAND_ONE_RANK)

        // New hand: different cards, incremented handNumber. Everything keyed on
        // either re-arms here, which is where the binding used to come apart.
        screen.advanceTo(handTwo())
        screen.assertFaceUp(HAND_TWO_RANK)

        // The tap that used to write to an orphaned MutableState.
        screen.tapHoleCards(HAND_TWO_RANK)
        screen.assertFaceDown(HAND_TWO_RANK)
    }

    /**
     * The complement of the above, so nobody "fixes" a failure by re-adding the
     * `remember` key and dropping the reset — that trade passes the first test
     * and reintroduces the bug.
     */
    @Test
    fun newHand_dealsFaceUp_evenIfThePreviousHandWasHidden() = runComposeUiTest {
        val screen = renderAcrossHands(handOne())

        screen.assertFaceUp(HAND_ONE_RANK)
        screen.tapHoleCards(HAND_ONE_RANK)
        screen.assertFaceDown(HAND_ONE_RANK)

        screen.advanceTo(handTwo())

        // Face-up without tapping. A hidden hand must not carry into a fresh deal.
        screen.assertFaceUp(HAND_TWO_RANK)
    }

    /**
     * Guards ENG-57. `Card` is a data class, so keying the hole-card slot on the
     * card alone reused the composition group whenever the same card came back
     * in the same seat on a later hand — leaving the deal-in state machine
     * `settled` and skipping the animation. Roughly one hand in 26.
     *
     * Repeating the *identical* pair is the amplified version of that: under
     * `key(card)` the slot never re-arms, so its manual-flip wrapper stays bound
     * to the previous hand.
     */
    @Test
    fun repeatedCards_stillReArmTheSlotOnANewHand() = runComposeUiTest {
        val screen = renderAcrossHands(handOne())

        screen.assertFaceUp(HAND_ONE_RANK)
        screen.tapHoleCards(HAND_ONE_RANK)
        screen.assertFaceDown(HAND_ONE_RANK)

        // Same cards, next hand — the case `key(card)` could not distinguish.
        screen.advanceTo(handOne(handNumber = 2))

        screen.assertFaceUp(HAND_ONE_RANK)
        screen.tapHoleCards(HAND_ONE_RANK)
        screen.assertFaceDown(HAND_ONE_RANK)
    }

    /** Three hands, to catch anything that survives one boundary but not two. */
    @Test
    fun tapToFlip_survivesThreeHands() = runComposeUiTest {
        val screen = renderAcrossHands(handOne())
        val hands = listOf(handOne() to HAND_ONE_RANK, handTwo() to HAND_TWO_RANK, handThree() to HAND_THREE_RANK)

        hands.forEachIndexed { index, (table, rank) ->
            if (index > 0) screen.advanceTo(table)
            screen.assertFaceUp(rank)
            screen.tapHoleCards(rank)
            screen.assertFaceDown(rank)
        }
    }

    /** A hand that ends and advances, the ordinary path between deals. */
    @Test
    fun tapToFlip_stillTogglesAfterAHandEndsAndAnotherBegins() = runComposeUiTest {
        val screen = renderAcrossHands(handOne())
        screen.assertFaceUp(HAND_ONE_RANK)

        screen.advanceTo(handOne(handNumber = 1, handResult = wonByFold()))
        screen.advanceTo(handTwo())

        screen.assertFaceUp(HAND_TWO_RANK)
        screen.tapHoleCards(HAND_TWO_RANK)
        screen.assertFaceDown(HAND_TWO_RANK)
    }

    /**
     * A board slot that has already flipped face-up, then loses its card inside
     * the same hand.
     *
     * `BoardSlot`'s `flipped` is only ever set true and never reset, and
     * `key(table.handNumber)` only re-arms it across hands — so this combination
     * used to take the `else` branch and dereference a null card, killing the
     * whole play screen out of composition. No server path emits it today, which
     * is exactly why it deserves a test rather than a comment: the invariant is
     * invisible and the next person to touch the projection cannot see it.
     */
    @Test
    fun boardSlotLosingItsCardMidHand_rendersABack_ratherThanCrashing() = runComposeUiTest {
        val fullBoard = listOf(
            Card(Rank.Two, Suit.Clubs),
            Card(Rank.Seven, Suit.Diamonds),
            Card(Rank.Ten, Suit.Hearts),
            Card(Rank.Jack, Suit.Spades),
            Card(Rank.Three, Suit.Clubs),
        )
        val screen = renderAcrossHands(handOne().copy(communityCards = fullBoard))
        screen.assertFaceUp(HAND_ONE_RANK)

        // Same hand number, board shrinks back to the flop.
        screen.advanceTo(handOne().copy(communityCards = fullBoard.take(3)))

        // Surviving the frame is the assertion; the old code threw here.
        screen.assertFaceUp(HAND_ONE_RANK)
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /**
     * Renders the screen over hoisted state and hands back a driver that can
     * swap the table underneath it, the way a new deal does in production.
     */
    private fun ComposeUiTest.renderAcrossHands(initial: TableUiState.Active): ScreenDriver {
        var state by mutableStateOf(PlayPokerState(table = initial))
        // Drive the clock by hand. The deal-in is a real animation under
        // Robolectric (LocalInspectionMode is false), and the manual-flip
        // wrapper only mounts once it settles — so polling for the rank glyph
        // returns too early, at `revealed`, 420ms before the tap target exists.
        // Explicit time also makes these deterministic and fast rather than
        // sleep-and-hope.
        mainClock.autoAdvance = false
        setContent {
            PreviewContent {
                PlayPokerScreen(state = state, onAction = {}, onBack = {})
            }
        }
        val driver = ScreenDriver(this) { state = PlayPokerState(table = it) }
        driver.settleDeal()
        return driver
    }

    private class ScreenDriver(
        private val test: ComposeUiTest,
        private val setTable: (TableUiState.Active) -> Unit,
    ) {
        fun advanceTo(table: TableUiState.Active) {
            setTable(table)
            // Pump the write BEFORE advancing time. With autoAdvance off,
            // advancing first leaves the state write unapplied and every
            // assertion afterwards reads the previous frame. These tests only
            // avoided that by accident — each advanceTo happened to follow a
            // performClick, which pumps — so a future non-click transition would
            // have silently asserted against stale state.
            test.waitForIdle()
            settleDeal()
        }

        /**
         * Runs the hole-card deal-in to completion: 150ms stagger + 320ms to
         * reveal + 420ms to settle, plus slack. Only once it settles does the
         * manual-flip wrapper mount, which is what the tap needs.
         */
        fun settleDeal() {
            test.mainClock.advanceTimeBy(DEAL_IN_MS)
            test.waitForIdle()
        }

        /**
         * Taps a hole card. The tap detector is on an ancestor Box and the card's
         * text doesn't consume pointer events, so a click on the rank glyph
         * reaches it. Then runs the 380ms flip past its halfway point, where the
         * face swaps to the back.
         */
        fun tapHoleCards(rank: String) {
            test.onAllNodesWithText(rank)[0].performClick()
            test.mainClock.advanceTimeBy(FLIP_MS)
            test.waitForIdle()
        }

        /** Asserts the cards are dealt and face-up: the rank glyph is present. */
        fun assertFaceUp(rank: String) {
            test.onAllNodesWithText(rank).assertCountEquals(EXPECTED_RANK_NODES)
        }

        /** Face-down means the rank glyph is gone — the back is Canvas-only. */
        fun assertFaceDown(rank: String) {
            test.onAllNodesWithText(rank).assertCountEquals(0)
        }
    }

    private companion object {
        const val HUMAN_SEAT = 0

        /** 150ms deal stagger + 320ms to reveal + 420ms to settle, plus slack. */
        const val DEAL_IN_MS = 1_200L

        /** The manual flip is a 380ms tween; past 90 degrees the back shows. */
        const val FLIP_MS = 500L

        /** One hole card carries the rank; the board is empty in these fixtures. */
        const val EXPECTED_RANK_NODES = 1
        const val HAND_ONE_RANK = "A"
        const val HAND_TWO_RANK = "Q"
        const val HAND_THREE_RANK = "8"
    
        fun handOne(handNumber: Int = 1, handResult: HandResultView? = null) = table(
            holeCards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
            handNumber = handNumber,
            handResult = handResult,
        )

        fun handTwo() = table(
            holeCards = listOf(Card(Rank.Queen, Suit.Hearts), Card(Rank.Nine, Suit.Clubs)),
            handNumber = 2,
        )

        fun handThree() = table(
            holeCards = listOf(Card(Rank.Eight, Suit.Diamonds), Card(Rank.Four, Suit.Hearts)),
            handNumber = 3,
        )

        fun wonByFold() = HandResultView(winners = emptyList(), board = emptyList())

        fun table(
            holeCards: List<Card>,
            handNumber: Int,
            handResult: HandResultView? = null,
        ): TableUiState.Active = TableUiState.Active(
            street = BettingRound.Preflop,
            communityCards = emptyList(),
            pot = 30,
            potCommittedThisStreet = 0,
            seats = listOf(
                SeatView(
                    index = HUMAN_SEAT,
                    displayName = "You",
                    stack = 980,
                    contributedThisStreet = 0,
                    isActing = true,
                    isHuman = true,
                    isBot = false,
                    avatarKey = null,
                    emoji = null,
                    holeCards = holeCards,
                    showHoleCardBacks = false,
                    participation = HandParticipation.InHand,
                    seatEmpty = false,
                    isBusted = false,
                    lastAction = null,
                    isDealer = true,
                    isSmallBlind = false,
                    isBigBlind = false,
                ),
                SeatView(
                    index = 1,
                    displayName = "David",
                    stack = 1_000,
                    contributedThisStreet = 0,
                    isActing = false,
                    isHuman = false,
                    isBot = true,
                    avatarKey = null,
                    emoji = null,
                    holeCards = emptyList(),
                    showHoleCardBacks = true,
                    participation = HandParticipation.InHand,
                    seatEmpty = false,
                    isBusted = false,
                    lastAction = null,
                    isDealer = false,
                    isSmallBlind = true,
                    isBigBlind = false,
                ),
            ),
            actingSeatIndex = HUMAN_SEAT,
            isHumanTurn = true,
            humanLegalActions = LegalActions(
                canCheck = false,
                canCall = true,
                callAmount = 20,
                canRaise = true,
                isOpenBet = false,
                minRaiseTotal = 40,
                maxRaiseTotal = 980,
                canAllIn = true,
                allInAmount = 980,
                potIfYouCall = 50,
            ),
            humanHandLabel = null,
            handResult = handResult,
            smallBlind = 10,
            bigBlind = 20,
            handNumber = handNumber,
            buttonSeatIndex = HUMAN_SEAT,
            smallBlindSeatIndex = 1,
            bigBlindSeatIndex = 1,
            practiceTierBotsPresent = false,
            waitingToBeDealtIn = false,
        )
    }
}
