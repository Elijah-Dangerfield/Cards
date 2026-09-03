package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.LegalActions
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.features.room.impl.session.NextHandCountdown
import com.dangerfield.cards.libraries.cards.BotAvatarEmoji
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
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
 * The table itself, rather than the screen chrome [PlayPokerScreenTest] covers:
 * what the felt renders per street, what each seat says about the player sitting
 * in it, what the money tier changes about the copy the player reads, and the
 * malformed snapshots that reach this screen when a seat leaves, a socket flaps,
 * or a winner is no longer at the table.
 *
 * Three conventions this file depends on:
 *
 *  - **Card faces need no test tags.** `PlayingCard` emits `rank.display` and
 *    `suit.symbol` as real `Text`; `PlayingCardBack` is Canvas-only. So the
 *    presence of the rank glyph *is* the face-up assertion. Every board fixture
 *    keeps its ranks disjoint from the hole cards' (A, K) and every bot seat
 *    carries [BotAvatarEmoji] the way the real projection does — an emoji-less
 *    seat falls back to its name's initial, and "Jane" would answer to a board
 *    assertion for the jack.
 *  - **The clock is driven by hand.** Under Robolectric `LocalInspectionMode` is
 *    false, so the board cascade and the hole-card deal-in are real animations.
 *    A hoisted state write also needs a `waitForIdle` *before* the clock is
 *    advanced — with `autoAdvance` off, advancing first leaves the write
 *    unapplied and every assertion reads the previous frame.
 *  - **Text assertions go through [assertShown].** A merged dialog node
 *    republishes its whole subtree's text, so plain `onNodeWithText` throws on
 *    ambiguity the moment a dialog and the felt below it both carry a string.
 *
 * State is hoisted for anything that has to *change* — a street advancing, a
 * seat leaving, a connection recovering. Those transitions are what single
 * static renders can't observe, and they're where this screen's per-hand
 * `remember`s and index lookups come apart.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The one deviation from the sibling files' @Config: a phone-sized viewport.
// Robolectric's default is 320×470px, shorter than any shipping device, and this
// screen is a full-height felt — the pot pill and the action slot measure to zero
// height there, which would make half of these assertions untestable for reasons
// no user will ever hit.
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PlayPokerScreenTableTest {

    // ── board and street progression ─────────────────────────────────────────

    /**
     * Preflop the board is five face-down backs. A regression that renders a
     * slot's card before its street shows the player cards nobody has been dealt
     * yet — the worst thing that can happen on a poker felt.
     */
    @Test
    fun preflop_showsNoBoardCardFaces() = runComposeUiTest {
        renderScreen(PlayPokerState(table = activeTable(communityCards = emptyList())))

        BOARD_RANKS.forEach { assertNotShown(it) }
    }

    /** The flop reveals exactly three — not the turn and river sitting behind it. */
    @Test
    fun flop_revealsTheThreeFlopCardsAndNothingElse() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(street = BettingRound.Flop, communityCards = flop()),
            ),
        )

        flopRanks().forEach { assertShown(it) }
        assertNotShown(TURN_RANK)
        assertNotShown(RIVER_RANK)
    }

    /** The river shows all five. Guards the last two slots never flipping. */
    @Test
    fun river_revealsAllFiveBoardCards() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(street = BettingRound.River, communityCards = river()),
            ),
        )

        BOARD_RANKS.forEach { assertShown(it) }
    }

    /**
     * The turn flips in place on the *live* hand. `BoardArea` is
     * `key(table.handNumber)`-scoped; re-keying it on the community cards
     * instead would tear down the flop's settled slots and re-deal the whole
     * board on every street.
     */
    @Test
    fun streetAdvance_flipsTheTurnCardWithoutRedealingTheFlop() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(table = activeTable(street = BettingRound.Flop, communityCards = flop())),
        )
        assertNotShown(TURN_RANK)

        screen.advanceTo(
            PlayPokerState(
                table = activeTable(street = BettingRound.Turn, communityCards = turn(), handNumber = 1),
            ),
        )

        assertShown(TURN_RANK)
        flopRanks().forEach { assertShown(it) }
    }

    /**
     * A new deal resets the felt. `BoardArea`'s `key(handNumber)` is what arms
     * that; drop it and the previous hand's river stays face-up under the fresh
     * hole cards.
     */
    @Test
    fun newHand_returnsTheBoardToFaceDown() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(table = activeTable(street = BettingRound.River, communityCards = river())),
        )
        BOARD_RANKS.forEach { assertShown(it) }

        screen.advanceTo(
            PlayPokerState(
                table = activeTable(
                    street = BettingRound.Preflop,
                    communityCards = emptyList(),
                    handNumber = 2,
                ),
            ),
        )

        BOARD_RANKS.forEach { assertNotShown(it) }
    }

    // ── seats ────────────────────────────────────────────────────────────────

    /** Who is at the table and what they can bet with — the row's whole job. */
    @Test
    fun opponentSeats_showNameAndStack() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David", stack = 1_000),
                        botSeat(index = 2, name = "Jane", stack = 2_450),
                    ),
                ),
            ),
        )

        assertShown("David")
        assertShown("Jane")
        assertShown("1k")
        assertShown("2.4k")
    }

    /**
     * Four opponents is exactly `PackedOpponentLimit` — the last table size that
     * still uses the packed (non-scrolling) row, where every seat has to fit on
     * screen at once. A packed row that overflows silently drops a player.
     */
    @Test
    fun fourOpponents_packedRow_showsEverySeat() = runComposeUiTest {
        renderScreen(
            PlayPokerState(table = activeTable(seats = seatsWithOpponents(4), actingSeatIndex = 4)),
        )

        (1..4).forEach { assertShown(opponentName(it)) }
    }

    /**
     * The fifth opponent pushes the row past `PackedOpponentLimit` and swaps
     * `PackedOpponentsRow` for `ScrollingOpponentsRow` — a different composable
     * with different item identity. Five still fit on a phone, so nothing here
     * depends on scrolling: the point is that the seats already at the table
     * survive the swap and the joiner appears.
     */
    @Test
    fun fifthOpponentJoins_swapsToTheScrollingRow_withoutLosingSeats() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(table = activeTable(seats = seatsWithOpponents(4))),
        )
        assertNotShown(opponentName(5))

        screen.advanceTo(PlayPokerState(table = activeTable(seats = seatsWithOpponents(5))))

        (1..5).forEach { assertShown(opponentName(it)) }
    }

    /**
     * At a full table the scrolling row is genuinely virtualised — the far seats
     * aren't composed at all. So when the action reaches one of them, the row has
     * to scroll it into view, or the player is left staring at a table where
     * nobody appears to be on the clock.
     */
    @Test
    fun fullTable_scrollingRow_scrollsTheSeatOnTheClockIntoView() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(table = activeTable(seats = seatsWithOpponents(8), actingSeatIndex = 1)),
        )
        assertNotShown(opponentName(8))

        screen.advanceTo(
            PlayPokerState(table = activeTable(seats = seatsWithOpponents(8), actingSeatIndex = 8)),
        )

        assertShown(opponentName(8))
    }

    /**
     * The fold cue pops in and stays for the hand (GAME-17) — without it a seat
     * that folded at hand start just greys out with no explanation.
     */
    @Test
    fun foldedOpponent_showsTheFoldChip() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David").copy(
                            participation = HandParticipation.Folded,
                            lastAction = PlayerAction.Fold,
                        ),
                        botSeat(index = 2, name = "Jane"),
                    ),
                ),
            ),
        )

        assertShown("FOLD")
    }

    /**
     * A busted seat swaps its chip count for the ✕. Leaving the stack rendered
     * would put a "0" next to a player who is out, and the player can no longer
     * tell who is still live for the pot.
     */
    @Test
    fun bustedOpponent_showsTheBustMarkAndDropsTheStack() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David", stack = 0).copy(isBusted = true),
                        botSeat(index = 2, name = "Jane", stack = 2_450),
                    ),
                ),
            ),
        )

        assertShown(BUST_MARK)
        assertNotShown("0")
        assertShown("2.4k")
    }

    /**
     * The mirror of the above. Stack 0 mid-hand is an all-in seat, not a busted
     * one — the projection only sets `isBusted` once the hand resolves. Deriving
     * "busted" from the stack in the renderer would ✕ out a player who is still
     * live for the pot they just shoved into.
     */
    @Test
    fun allInSeatWithZeroStack_isNotRenderedAsBusted() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David", stack = 0).copy(
                            participation = HandParticipation.AllIn,
                            isBusted = false,
                            lastAction = PlayerAction.AllIn(amount = 1_000),
                        ),
                        botSeat(index = 2, name = "Jane"),
                    ),
                ),
            ),
        )

        assertNotShown(BUST_MARK)
        assertShown("0")
        assertShown("ALL-IN")
    }

    /**
     * An unoccupied chair carries no player and no bust state. Rendering it from
     * the stack alone (0 chips) would ✕ out a seat nobody is sitting in, and the
     * seats either side must be untouched.
     */
    @Test
    fun emptySeat_rendersAsEmpty_notAsABustedPlayer() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        emptySeat(index = 1),
                        botSeat(index = 2, name = "Jane", stack = 2_450),
                    ),
                ),
            ),
        )

        assertNotShown(BUST_MARK)
        assertShown("Jane")
        assertShown("2.4k")
    }

    /**
     * At showdown the under-avatar slot morphs from the stack into the seat's
     * revealed hole cards — the felt-native showdown that replaced the
     * full-screen dialog on money games. Asserted on a real-chip table
     * specifically because that is the tier with no result dialog to fall back
     * on: if the felt doesn't reveal, nothing does.
     */
    @Test
    fun realChipShowdown_revealsOpponentHoleCardsInPlaceOfTheirStack() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    street = BettingRound.Complete,
                    communityCards = river(),
                    actingSeatIndex = null,
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David", stack = 1_000).copy(
                            holeCards = listOf(
                                Card(Rank.Nine, Suit.Diamonds),
                                Card(Rank.Three, Suit.Clubs),
                            ),
                        ),
                    ),
                    handResult = HandResultView(
                        winners = listOf(winner(seatIndex = 1, amount = 240)),
                        board = river(),
                    ),
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown("9")
        assertShown("3")
        assertNotShown("1k")
    }

    /**
     * The winner's take is named in gold under their seat for the whole showdown
     * window — the persistent companion to the coins that fly there. Without it
     * the only signal that a seat won is a glow.
     */
    @Test
    fun winningSeat_showsTheAmountItTook() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    street = BettingRound.Complete,
                    communityCards = river(),
                    actingSeatIndex = null,
                    handResult = HandResultView(
                        winners = listOf(winner(seatIndex = 1, amount = 2_400)),
                        board = river(),
                    ),
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown("+2.4k")
    }

    // ── money tiers ──────────────────────────────────────────────────────────

    /**
     * A private bots-only practice table must say so: real chips are off the
     * table. The explainer auto-opens on arrival precisely so the player knows
     * the terms before they play.
     */
    @Test
    fun practiceBotsOnly_explainerSaysRealChipsAreNotAtStake() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    practiceTierBotsPresent = true,
                    practiceTierBotsOnly = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown(NO_REAL_CHIPS, substring = true)
        assertNotShown(KEEP_WHAT_YOU_WIN, substring = true)
    }

    /**
     * The public disclosed-bot subsidy is the same table shape with the opposite
     * money story — chips ARE real and yours to keep. Falling through to the
     * practice copy here tells a player their winnings don't count when they do.
     */
    @Test
    fun subsidizedBotTable_explainerSaysYouKeepWhatYouWin() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    practiceTierBotsPresent = true,
                    practiceTierBotsOnly = true,
                    subsidizedBotTable = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown(KEEP_WHAT_YOU_WIN, substring = true)
        assertNotShown(NO_REAL_CHIPS, substring = true)
    }

    /**
     * Busting a real-chip MP seat is a wallet decision, not the solo auto-rebuy.
     * "Deal me in" here would promise a free refill that isn't coming.
     */
    @Test
    fun realMultiplayerBust_offersRebuy_notDealMeIn() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = bustedHumanTable(),
                xpMode = XpMode.MULTIPLAYER,
                chipBalance = 5_000,
            ),
        )

        assertShown(MP_BUST_TITLE)
        assertShown("Rebuy (1,000)")
        assertNotShown(DEAL_ME_IN)
    }

    /**
     * A wallet that can't cover the buy-in gets the top-up path instead.
     * Offering a rebuy the server can only refuse is the dead-tap class of bug
     * that MP-38 was about.
     */
    @Test
    fun realMultiplayerBust_withoutEnoughChips_offersBuyChips() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = bustedHumanTable(),
                xpMode = XpMode.MULTIPLAYER,
                chipBalance = 120,
            ),
        )

        assertShown(BUY_CHIPS)
        assertNotShown("Rebuy (1,000)")
    }

    /**
     * Busting on the subsidized table is a house-funded refill of REAL chips, so
     * it must not fall through to the practice "chips don't count" body.
     */
    @Test
    fun subsidizedBust_saysFreshStackOnTheHouse_notPracticeChips() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = bustedHumanTable(
                    practiceTierBotsPresent = true,
                    practiceTierBotsOnly = true,
                    subsidizedBotTable = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown(SUBSIDIZED_BUST_BODY, substring = true)
        assertNotShown(PRACTICE_BUST_BODY, substring = true)
    }

    /**
     * The north star: on a real-chip table a finished hand hands the player the
     * felt countdown and a one-tap cash-out, not a modal to dismiss. A result
     * dialog here buries "Leave with winnings" behind a scrim for the entire
     * window it is available.
     */
    @Test
    fun realChipTable_handEnd_offersLeaveWithWinnings_insteadOfTheResultDialog() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    street = BettingRound.Complete,
                    communityCards = river(),
                    actingSeatIndex = null,
                    handResult = HandResultView(
                        winners = listOf(winner(seatIndex = HUMAN_SEAT, amount = 240)),
                        board = river(),
                    ),
                ),
                xpMode = XpMode.MULTIPLAYER,
                nextHandCountdown = NextHandCountdown(deadlineEpochMs = 0L),
            ),
        )

        assertShown(LEAVE_WITH_WINNINGS)
        assertNotShown(NEXT_HAND)
    }

    /**
     * The mirror image: a practice hand keeps the celebratory modal and waits for
     * a tap. Leaking the real-chip countdown here would offer to cash out chips
     * that were never real.
     */
    @Test
    fun practiceTable_handEnd_keepsTheResultDialog_notTheCountdown() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    street = BettingRound.Complete,
                    communityCards = river(),
                    actingSeatIndex = null,
                    handResult = HandResultView(
                        winners = listOf(winner(seatIndex = HUMAN_SEAT, amount = 240)),
                        board = river(),
                    ),
                ),
                nextHandCountdown = NextHandCountdown(deadlineEpochMs = 0L),
            ),
        )

        assertShown(NEXT_HAND)
        assertNotShown(LEAVE_WITH_WINNINGS)
    }

    /**
     * Leaving a real-money seat mid-hand states the net settling to the wallet
     * and the chips already committed that leaving forfeits (ROOM-4). Both lines
     * are gated on the money tier, so a tier misread silently drops the math the
     * player is about to act on.
     */
    @Test
    fun realMultiplayerLeave_confirmNamesTheSettlementAndTheForfeit() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(stack = 1_250).copy(contributedThisHand = 50),
                        botSeat(index = 1, name = "David"),
                    ),
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        onNodeWithContentDescription("Back").performClick()
        settle()

        assertShown("settles +250", substring = true)
        assertShown("Includes 50", substring = true)
    }

    // ── connection ───────────────────────────────────────────────────────────

    /**
     * The banner has to come DOWN. A stuck "connection lost" on a healthy socket
     * tells a player their money game is broken while they're winning it, and no
     * static per-state render can observe the transition.
     */
    @Test
    fun connectionRecovers_bannerDisappears() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(
                table = activeTable(),
                connection = ConnectionState.Reconnecting,
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        assertShown(CONNECTION_LOST, substring = true)

        screen.advanceTo(
            PlayPokerState(
                table = activeTable(),
                connection = ConnectionState.Connected,
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertNotShown(CONNECTION_LOST, substring = true)
    }

    /**
     * Dropping to [ConnectionState.Disconnected] — the terminal state, which the
     * static tests next door never render — banners the problem without taking
     * the felt away. The player keeps seeing the hand their chips are in.
     */
    @Test
    fun connectionDropsMidHand_bannersWithoutTearingDownTheTable() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(
                table = activeTable(street = BettingRound.Flop, communityCards = flop()),
                connection = ConnectionState.Connected,
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        assertNotShown(CONNECTION_LOST, substring = true)

        screen.advanceTo(
            PlayPokerState(
                table = activeTable(street = BettingRound.Flop, communityCards = flop()),
                connection = ConnectionState.Disconnected,
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown(CONNECTION_LOST, substring = true)
        flopRanks().forEach { assertShown(it) }
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    /**
     * A seatless Active snapshot reaches this screen — a room that emptied, a
     * scrubbed snapshot. Every `seats.first { it.isHuman }` on the render path
     * has to be the `firstOrNull` variant or the felt takes the process with it.
     */
    @Test
    fun emptySeatsList_stillRendersTheTable() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(seats = emptyList(), actingSeatIndex = null),
            ),
        )

        onNodeWithContentDescription("Back").assertIsDisplayed()
        assertShown("30")
    }

    /**
     * The winner left before the result landed, so no seat carries their index.
     * The by-fold headline resolves the name off the seat list; a missing seat
     * has to fall back to "Player" rather than crash or render a blank headline.
     */
    @Test
    fun handResultWinnerNoLongerSeated_fallsBackToThePlayerLabel() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    street = BettingRound.Complete,
                    actingSeatIndex = null,
                    handResult = HandResultView(
                        winners = listOf(winner(seatIndex = 7, amount = 120, byFold = true)),
                        board = emptyList(),
                    ),
                ),
            ),
        )

        assertShown("Player wins by fold")
    }

    /**
     * A seat leaving mid-hand shrinks `seats` under a live composition. Anything
     * holding an index into the previous list — the opponents row's per-item
     * lookups most of all — resolves against a shorter list on the next frame.
     */
    @Test
    fun seatLeavesMidHand_dropsThatSeatAndKeepsTheRest() = runComposeUiTest {
        val screen = renderHoisted(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David"),
                        botSeat(index = 2, name = "Jane"),
                        botSeat(index = 3, name = "Mike"),
                    ),
                ),
            ),
        )
        assertShown("Jane")

        screen.advanceTo(
            PlayPokerState(
                table = activeTable(
                    seats = listOf(
                        humanSeat(),
                        botSeat(index = 1, name = "David"),
                        botSeat(index = 3, name = "Mike"),
                    ),
                ),
            ),
        )

        assertNotShown("Jane")
        assertShown("David")
        assertShown("Mike")
    }

    /**
     * A mid-game joiner holds no seat, no hand and no legal actions, so the whole
     * bottom half of the felt would render blank and read as a stuck table
     * (GAME-2). The placeholder is what makes the wait legible.
     */
    @Test
    fun waitingToBeDealtIn_fillsTheSeatSlotWithTheSpectatingNotice() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(waitingToBeDealtIn = true),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        assertShown(SPECTATING)
    }

    /**
     * Seven-figure stacks are reachable through chip packs and a hot table. The
     * seat tile is locked to the hole-card row height and the pot sits in a
     * fixed slot, so raw digits overflow both — every chip figure on the felt
     * goes through the compact formatter.
     */
    @Test
    fun sevenFigureChipCounts_renderCompact() = runComposeUiTest {
        renderScreen(
            PlayPokerState(
                table = activeTable(
                    pot = 2_400_000,
                    seats = listOf(
                        humanSeat(stack = 12_500_000),
                        botSeat(index = 1, name = "David", stack = 4_250_000),
                    ),
                ),
            ),
        )

        assertShown("2.4M")
        assertShown("12M")
        assertShown("4.2M")
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private fun ComposeUiTest.renderScreen(state: PlayPokerState) {
        mainClock.autoAdvance = false
        setContent {
            PreviewContent {
                PlayPokerScreen(state = state, onAction = {}, onBack = {})
            }
        }
        settle()
    }

    /**
     * Renders over hoisted state and hands back a driver that swaps the whole
     * [PlayPokerState] underneath, the way a fresh snapshot does in production.
     */
    private fun ComposeUiTest.renderHoisted(initial: PlayPokerState): ScreenDriver {
        var state by mutableStateOf(initial)
        mainClock.autoAdvance = false
        setContent {
            PreviewContent {
                PlayPokerScreen(state = state, onAction = {}, onBack = {})
            }
        }
        settle()
        return ScreenDriver(this) { state = it }
    }

    /**
     * Runs the board cascade (70ms stagger ×4 + 240ms drop + 130ms flip stagger
     * ×2 + 380ms flip) and the hole-card deal-in (150 + 320 + 420) to completion,
     * plus slack for the result overlays.
     *
     * The leading `waitForIdle` is load-bearing: with `autoAdvance` off, a state
     * write made between frames is not picked up by `advanceTimeBy` alone, so
     * advancing first would settle the animations of the *previous* state.
     */
    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(SETTLE_MS)
        waitForIdle()
    }

    /**
     * Asserts some laid-out node carries exactly [text].
     *
     * Not `onNodeWithText(...).assertIsDisplayed()`: an open dialog publishes a
     * merged node carrying its whole subtree's text, so the singular matcher
     * throws on ambiguity as soon as two surfaces are on screen. Requiring a
     * non-zero size keeps the check honest — a node that composed but measured
     * to nothing is not on the table.
     */
    private fun ComposeUiTest.assertShown(text: String, substring: Boolean = false) {
        val nodes = onAllNodesWithText(text, substring = substring).fetchSemanticsNodes()
        assertTrue(
            nodes.any { it.size.width > 0 && it.size.height > 0 },
            "expected \"$text\" on the table, found ${nodes.size} node(s)",
        )
    }

    private fun ComposeUiTest.assertNotShown(text: String, substring: Boolean = false) {
        val nodes = onAllNodesWithText(text, substring = substring).fetchSemanticsNodes()
        assertTrue(nodes.isEmpty(), "did not expect \"$text\" on the table")
    }

    private class ScreenDriver(
        private val test: ComposeUiTest,
        private val setState: (PlayPokerState) -> Unit,
    ) {
        fun advanceTo(state: PlayPokerState) {
            setState(state)
            test.waitForIdle()
            test.mainClock.advanceTimeBy(SETTLE_MS)
            test.waitForIdle()
        }
    }

    private companion object {
        const val HUMAN_SEAT = 0

        /** Board cascade + hole-card deal-in + overlay transitions, with slack. */
        const val SETTLE_MS = 2_500L

        // Real copy, from libraries/resources .../composeResources/values/strings.xml.
        const val CONNECTION_LOST = "Connection lost"
        const val SPECTATING = "You're spectating this hand"
        const val NEXT_HAND = "Next hand"
        const val LEAVE_WITH_WINNINGS = "Leave with winnings"
        const val DEAL_ME_IN = "Deal me in"
        const val BUY_CHIPS = "Buy chips"
        const val MP_BUST_TITLE = "You're out of chips"
        const val SUBSIDIZED_BUST_BODY = "Fresh stack on the house"
        const val PRACTICE_BUST_BODY = "Practice chips refilled"
        const val NO_REAL_CHIPS = "real chips are no longer at stake"
        const val KEEP_WHAT_YOU_WIN = "yours to keep, on the house"

        /** Rendered by `OpponentSeat` over a busted seat's avatar. */
        const val BUST_MARK = "✕"

        // Board ranks stay disjoint from the hole cards' (A, K) so a board
        // assertion can never be satisfied by the player's own hand.
        const val TURN_RANK = "J"
        const val RIVER_RANK = "Q"
        val BOARD_RANKS = listOf("2", "7", "10", TURN_RANK, RIVER_RANK)

        fun flopRanks(): List<String> = BOARD_RANKS.take(3)

        fun flop(): List<Card> = listOf(
            Card(Rank.Two, Suit.Clubs),
            Card(Rank.Seven, Suit.Diamonds),
            Card(Rank.Ten, Suit.Hearts),
        )

        fun turn(): List<Card> = flop() + Card(Rank.Jack, Suit.Spades)

        fun river(): List<Card> = turn() + Card(Rank.Queen, Suit.Clubs)

        fun opponentName(index: Int): String = "Bot$index"

        fun winner(seatIndex: Int, amount: Long, byFold: Boolean = false): HandWinner =
            HandWinner(seatIndex = seatIndex, amount = amount, handRank = null, byFold = byFold)

        fun humanSeat(stack: Long = 980): SeatView = SeatView(
            index = HUMAN_SEAT,
            displayName = "You",
            stack = stack,
            contributedThisStreet = 0,
            isActing = false,
            isHuman = true,
            isBot = false,
            avatarKey = null,
            emoji = null,
            holeCards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
            showHoleCardBacks = false,
            participation = HandParticipation.InHand,
            seatEmpty = false,
            isBusted = false,
            lastAction = null,
            isDealer = true,
            isSmallBlind = false,
            isBigBlind = false,
        )

        /**
         * A bot seat the way `SeatView.fromSeat` projects one — every bot reads
         * as a bot, so the avatar carries [BotAvatarEmoji] rather than falling
         * back to the name's initial.
         */
        fun botSeat(index: Int, name: String, stack: Long = 1_000): SeatView = SeatView(
            index = index,
            displayName = name,
            stack = stack,
            contributedThisStreet = 0,
            isActing = false,
            isHuman = false,
            isBot = true,
            avatarKey = null,
            emoji = BotAvatarEmoji,
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

        /**
         * An unoccupied chair, as the projection emits one: no player id (so no
         * badge, no tenure, no emoji), never dealt in, and explicitly not busted
         * despite the zero stack.
         */
        fun emptySeat(index: Int): SeatView = botSeat(index = index, name = "", stack = 0).copy(
            userId = null,
            isBot = false,
            emoji = null,
            showHoleCardBacks = false,
            participation = HandParticipation.NotDealt,
            seatEmpty = true,
            isBusted = false,
        )

        fun seatsWithOpponents(count: Int): List<SeatView> =
            listOf(humanSeat()) + (1..count).map { botSeat(index = it, name = opponentName(it)) }

        fun legalActions(): LegalActions = LegalActions(
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
        )

        fun activeTable(
            street: BettingRound = BettingRound.Preflop,
            communityCards: List<Card> = emptyList(),
            pot: Long = 30,
            seats: List<SeatView> = listOf(
                humanSeat(),
                botSeat(index = 1, name = "David"),
                botSeat(index = 2, name = "Jane"),
            ),
            actingSeatIndex: Int? = 1,
            handResult: HandResultView? = null,
            handNumber: Int = 1,
            practiceTierBotsPresent: Boolean = false,
            practiceTierBotsOnly: Boolean = false,
            subsidizedBotTable: Boolean = false,
            waitingToBeDealtIn: Boolean = false,
        ): TableUiState.Active {
            val isHumanTurn = actingSeatIndex == HUMAN_SEAT
            return TableUiState.Active(
                street = street,
                communityCards = communityCards,
                pot = pot,
                potCommittedThisStreet = 0,
                seats = seats.map { it.copy(isActing = it.index == actingSeatIndex) },
                actingSeatIndex = actingSeatIndex,
                isHumanTurn = isHumanTurn,
                humanLegalActions = if (isHumanTurn) legalActions() else null,
                humanHandLabel = null,
                handResult = handResult,
                smallBlind = 10,
                bigBlind = 20,
                buyIn = 1_000,
                handNumber = handNumber,
                buttonSeatIndex = HUMAN_SEAT,
                smallBlindSeatIndex = 1,
                bigBlindSeatIndex = 2,
                practiceTierBotsPresent = practiceTierBotsPresent,
                practiceTierBotsOnly = practiceTierBotsOnly,
                subsidizedBotTable = subsidizedBotTable,
                waitingToBeDealtIn = waitingToBeDealtIn,
            )
        }

        /**
         * A finished hand the human lost their whole stack in, by fold — no
         * showdown to reveal, so the bust surface shows straight away.
         */
        fun bustedHumanTable(
            practiceTierBotsPresent: Boolean = false,
            practiceTierBotsOnly: Boolean = false,
            subsidizedBotTable: Boolean = false,
        ): TableUiState.Active = activeTable(
            street = BettingRound.Complete,
            actingSeatIndex = null,
            seats = listOf(
                humanSeat(stack = 0),
                botSeat(index = 1, name = "David", stack = 2_000),
            ),
            handResult = HandResultView(
                winners = listOf(winner(seatIndex = 1, amount = 60, byFold = true)),
                board = emptyList(),
            ),
            practiceTierBotsPresent = practiceTierBotsPresent,
            practiceTierBotsOnly = practiceTierBotsOnly,
            subsidizedBotTable = subsidizedBotTable,
        )
    }
}
