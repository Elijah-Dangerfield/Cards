package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.LegalActions
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Host-side Compose UI tests for [PlayPokerScreen] (MP-2). Each test drives the
 * screen into one of its distinct rendered states and asserts on the top-level,
 * state-keyed chrome the screen owns directly — the connection banner, the
 * loading placeholder, the dealt-in / practice-tier labels, and the back
 * affordance. Those signals are stable across DS-component churn underneath;
 * asserting on deep action-bar internals would couple the test to layout it
 * doesn't own.
 *
 * "Test the seams in production order" (practices/testing.md): states are built
 * the way the real projection emits them rather than poked field-by-field.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class PlayPokerScreenTest {

    @Test
    fun loadingTable_showsDealingInPlaceholder() = runComposeUiTest {
        renderScreen(PlayPokerState(table = TableUiState.Loading))
        onNodeWithText("Dealing in…").assertIsDisplayed()
    }

    @Test
    fun loadingTable_showsBackAffordance() = runComposeUiTest {
        renderScreen(PlayPokerState(table = TableUiState.Loading))
        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun yourTurn_doesNotShowConnectionBanner() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(isHumanTurn = true, actingSeatIndex = HUMAN_SEAT),
                connection = ConnectionState.Connected,
            ),
        )
        onNodeWithText(CONNECTION_LOST, substring = true).assertDoesNotExist()
    }

    @Test
    fun botThinking_rendersTableWithoutCrashing() = runComposeUiTest {
        // Not the human's turn — a bot seat is acting. The action bar is
        // suppressed; the table still composes.
        renderScreen(
            PlayPokerState(
                table = activeTable(isHumanTurn = false, actingSeatIndex = 1),
            ),
        )
        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun raiseUnavailable_rendersTable() = runComposeUiTest {
        // Short-stacked human: legal to call/fold but cannot raise. The screen
        // must still render its turn UI without the raise affordance.
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = true,
                    actingSeatIndex = HUMAN_SEAT,
                    legalActions = legalActions(canRaise = false),
                ),
            ),
        )
        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun showdown_rendersHandResult() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = false,
                    actingSeatIndex = null,
                    street = BettingRound.Showdown,
                    communityCards = board(),
                    handResult = HandResultView(
                        winners = listOf(
                            HandWinner(seatIndex = HUMAN_SEAT, amount = 200, handRank = null, byFold = false),
                        ),
                        board = board(),
                    ),
                ),
            ),
        )
        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun foldAround_rendersByFoldResult() = runComposeUiTest {
        // Everyone folds to the human preflop — win by fold, no board.
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = false,
                    actingSeatIndex = null,
                    street = BettingRound.Preflop,
                    communityCards = emptyList(),
                    handResult = HandResultView(
                        winners = listOf(
                            HandWinner(seatIndex = HUMAN_SEAT, amount = 30, handRank = null, byFold = true),
                        ),
                        board = emptyList(),
                    ),
                ),
            ),
        )
        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun soloBustAtShowdown_showsRevealBeforeBustDialog() = runComposeUiTest {
        // GAME-18: the human busts at showdown against bots. The showdown
        // reveal must show first — the bust dialog used to replace it
        // entirely, hiding the hand the player lost to.
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = false,
                    actingSeatIndex = null,
                    street = BettingRound.Showdown,
                    communityCards = board(),
                    handResult = HandResultView(
                        winners = listOf(
                            HandWinner(seatIndex = 1, amount = 2_020, handRank = null, byFold = false),
                        ),
                        board = board(),
                    ),
                    humanStack = 0,
                ),
            ),
        )
        onNodeWithText("Showdown").assertIsDisplayed()
        onNodeWithText("You went bust").assertDoesNotExist()

        onNodeWithText("Continue").performClick()
        waitForIdle()
        onNodeWithText("You went bust").assertIsDisplayed()
    }

    @Test
    fun soloBustByFold_skipsRevealAndShowsBustDialog() = runComposeUiTest {
        // No showdown happened (everyone folded), so there's nothing to
        // reveal — the bust dialog shows straight away.
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = false,
                    actingSeatIndex = null,
                    street = BettingRound.Preflop,
                    communityCards = emptyList(),
                    handResult = HandResultView(
                        winners = listOf(
                            HandWinner(seatIndex = 1, amount = 30, handRank = null, byFold = true),
                        ),
                        board = emptyList(),
                    ),
                    humanStack = 0,
                ),
            ),
        )
        onNodeWithText("You went bust").assertIsDisplayed()
    }

    @Test
    fun connectionLost_showsBanner() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(isHumanTurn = false, actingSeatIndex = 1),
                connection = ConnectionState.Reconnecting,
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        onNodeWithText(CONNECTION_LOST, substring = true).assertIsDisplayed()
    }

    @Test
    fun connected_hidesBanner() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(isHumanTurn = true, actingSeatIndex = HUMAN_SEAT),
                connection = ConnectionState.Connected,
            ),
        )
        onNodeWithText(CONNECTION_LOST, substring = true).assertDoesNotExist()
    }

    @Test
    fun practiceTier_showsLabel() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = false,
                    actingSeatIndex = 1,
                    practiceTierBotsPresent = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        // The on-table label carries the unique "· bots present" suffix; the
        // auto-shown explainer dialog's title is the bare "Practice tier", so
        // match the full label to avoid colliding with it.
        onNodeWithText("bots present", substring = true).assertIsDisplayed()
    }

    @Test
    fun waitingToBeDealtIn_showsLabel() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    isHumanTurn = false,
                    actingSeatIndex = 1,
                    waitingToBeDealtIn = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        onNodeWithText("dealt in", substring = true).assertIsDisplayed()
    }

    @Test
    fun backAffordance_invokesRequestLeave_whenNoHandInProgress() = runComposeUiTest {
        var leftTable = false
        renderScreen(
            state = PlayPokerState(table = TableUiState.Loading),
            onAction = { if (it is PlayPokerAction.LeaveTable) leftTable = true },
        )
        // Loading table has no hand in progress, so back leaves immediately
        // without the confirm dialog.
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        assertTrue(leftTable, "tapping back on a loading table fires LeaveTable")
    }

    private fun ComposeUiTest.renderScreen(
        state: PlayPokerState,
        onAction: (PlayPokerAction) -> Unit = {},
    ) {
        setContent {
            PreviewContent {
                PlayPokerScreen(
                    state = state,
                    onAction = onAction,
                    onBack = {},
                )
            }
        }
        waitForIdle()
    }

    private companion object {
        const val HUMAN_SEAT = 0
        const val CONNECTION_LOST = "Connection lost"

        fun card(rank: Rank, suit: Suit): Card = Card(rank, suit)

        fun board(): List<Card> = listOf(
            card(Rank.Two, Suit.Clubs),
            card(Rank.Seven, Suit.Diamonds),
            card(Rank.Ten, Suit.Hearts),
            card(Rank.Jack, Suit.Spades),
            card(Rank.Queen, Suit.Clubs),
        )

        fun humanSeat(stack: Long = 980, isActing: Boolean = false): SeatView = SeatView(
            index = HUMAN_SEAT,
            displayName = "You",
            stack = stack,
            contributedThisStreet = 0,
            isActing = isActing,
            isHuman = true,
            isBot = false,
            avatarKey = null,
            emoji = null,
            holeCards = listOf(card(Rank.Ace, Suit.Spades), card(Rank.King, Suit.Spades)),
            showHoleCardBacks = false,
            participation = HandParticipation.InHand,
            seatEmpty = false,
            isBusted = false,
            lastAction = null,
            isDealer = true,
            isSmallBlind = false,
            isBigBlind = false,
        )

        fun botSeat(index: Int, name: String, isActing: Boolean = false): SeatView = SeatView(
            index = index,
            displayName = name,
            stack = 1_000,
            contributedThisStreet = 0,
            isActing = isActing,
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
            isSmallBlind = index == 1,
            isBigBlind = index == 2,
        )

        fun legalActions(canRaise: Boolean = true): LegalActions = LegalActions(
            canCheck = false,
            canCall = true,
            callAmount = 20,
            canRaise = canRaise,
            isOpenBet = false,
            minRaiseTotal = 40,
            maxRaiseTotal = 980,
            canAllIn = true,
            allInAmount = 980,
            potIfYouCall = 50,
        )

        fun activeTable(
            isHumanTurn: Boolean,
            actingSeatIndex: Int?,
            street: BettingRound = BettingRound.Preflop,
            communityCards: List<Card> = emptyList(),
            legalActions: LegalActions? = legalActions(),
            handResult: HandResultView? = null,
            practiceTierBotsPresent: Boolean = false,
            waitingToBeDealtIn: Boolean = false,
            humanStack: Long = 980,
        ): TableUiState.Active = TableUiState.Active(
            street = street,
            communityCards = communityCards,
            pot = 30,
            potCommittedThisStreet = 0,
            seats = listOf(
                humanSeat(stack = humanStack, isActing = actingSeatIndex == HUMAN_SEAT),
                botSeat(index = 1, name = "David", isActing = actingSeatIndex == 1),
                botSeat(index = 2, name = "Jane", isActing = actingSeatIndex == 2),
            ),
            actingSeatIndex = actingSeatIndex,
            isHumanTurn = isHumanTurn,
            humanLegalActions = if (isHumanTurn) legalActions else null,
            humanHandLabel = null,
            handResult = handResult,
            smallBlind = 10,
            bigBlind = 20,
            handNumber = 1,
            buttonSeatIndex = HUMAN_SEAT,
            smallBlindSeatIndex = 1,
            bigBlindSeatIndex = 2,
            practiceTierBotsPresent = practiceTierBotsPresent,
            waitingToBeDealtIn = waitingToBeDealtIn,
        )
    }
}
