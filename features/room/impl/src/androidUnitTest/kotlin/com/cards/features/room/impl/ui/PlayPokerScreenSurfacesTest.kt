package com.dangerfield.cards.features.room.impl.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.LegalActions
import com.dangerfield.cards.features.room.impl.MatchOverResult
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.libraries.cards.XpMode
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose tests for the play screen's **modal surfaces** and its **leave flow**.
 *
 * [PlayPokerScreenTest] renders one state per test and asserts on the table
 * chrome; [PlayPokerScreenMultiHandTest] crosses a hand boundary to catch
 * per-hand `remember` bugs. Neither touches the ~19 overlays this screen owns,
 * which is where the two properties nobody had pinned down live:
 *
 *  1. **One blocking surface at a time.** Fifteen of those overlays are
 *     screen-local `remember`d booleans and four are VM-owned state flags.
 *     Nothing coordinates them, so any pair that can be true together renders
 *     together. The collision tests below name the pairs that are reachable.
 *  2. **No leaks across a hand boundary.** Only `actionSheetOpen` is
 *     force-closed (a `LaunchedEffect` on `isHumanTurn`). Every other surface
 *     is an unkeyed `remember` and survives the deal. Some of those are right;
 *     the tests record what actually happens either way, so a change of
 *     behaviour is visible rather than silent.
 *
 * Copy comes from `libraries/resources/.../strings.xml` verbatim — the real
 * user-visible text, not test tags, so a copy change that breaks a user's
 * ability to find a surface breaks a test too.
 *
 * **Not covered, deliberately.** The pot / stack / bet-pill / hand-label
 * explainers, the swipe-fold confirm, the report sheet, the badge-detail sheet
 * and the achievement celebration all open off felt affordances that carry no
 * stable, unique semantics to aim at (an unlabelled `clickable` on a chip, a
 * drag gesture, or a surface only reachable *through* another sheet). Driving
 * them would mean asserting against layout coordinates or adding test tags to
 * production code; neither earns its keep here. They're named so the gap is a
 * decision rather than an oversight.
 *
 * **System back is driven through the activity's own `OnBackPressedDispatcher`.**
 * The DS `Dialog` renders in-composition, so its `BackHandler` and the screen's
 * sit on that one dispatcher and the topmost enabled callback wins — exactly
 * the production ordering. The DS `BottomSheet` wraps M3 `ModalBottomSheet`,
 * which owns a separate window and its own dispatcher, so back is deliberately
 * not used against sheets here: it would prove something about the harness
 * rather than the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class PlayPokerScreenSurfacesTest {

    // ── VM-owned surfaces: open, absent, and driven by state ─────────────────

    /** Catches the board-tap cheat sheet silently not opening off `cheatSheetOpen`. */
    @Test
    fun cheatSheetOpen_showsHandRankings() = runComposeUiTest {
        render(PlayPokerState(table = table(), cheatSheetOpen = true))
        awaitText(RANKINGS_HEADING)
        onNodeWithText(RANKINGS_HEADING).assertIsDisplayed()
    }

    /** The other half: the sheet must not be up on an ordinary live table. */
    @Test
    fun cheatSheetClosed_handRankingsAbsent() = runComposeUiTest {
        render(PlayPokerState(table = table(), cheatSheetOpen = false))
        onNodeWithText(RANKINGS_HEADING).assertDoesNotExist()
    }

    /** Catches the quick-buy upsell not opening when the VM sets `quickBuyOpen`. */
    @Test
    fun quickBuyOpen_showsChipPackSheet() = runComposeUiTest {
        render(PlayPokerState(table = table(), quickBuyOpen = true))
        awaitText(QUICK_BUY_SUBTITLE)
        onNodeWithText(QUICK_BUY_SUBTITLE).assertIsDisplayed()
    }

    @Test
    fun quickBuyClosed_chipPackSheetAbsent() = runComposeUiTest {
        render(PlayPokerState(table = table(), quickBuyOpen = false))
        onNodeWithText(QUICK_BUY_SUBTITLE).assertDoesNotExist()
    }

    /**
     * MP-14: the heads-up match-over overlay is terminal. Catches a regression
     * that drops it and leaves the player staring at a dead table.
     */
    @Test
    fun matchOverResult_showsTerminalDialog() = runComposeUiTest {
        render(
            PlayPokerState(
                table = table(),
                xpMode = XpMode.MULTIPLAYER,
                matchOverResult = MatchOverResult(localPlayerWon = true),
            ),
        )
        onNodeWithText(MATCH_OVER_WON).assertIsDisplayed()
    }

    /**
     * Dismissing it must tear the session down *and* route off. Catches a
     * regression where "Done" closes the dialog but strands the player on the
     * dead table, or pops without the `LeaveGameFromBust` teardown.
     */
    @Test
    fun matchOverResultDismissed_tearsDownAndRoutesOff() = runComposeUiTest {
        val screen = render(
            PlayPokerState(
                table = table(),
                xpMode = XpMode.MULTIPLAYER,
                matchOverResult = MatchOverResult(localPlayerWon = false),
            ),
        )

        onNodeWithText(MATCH_OVER_DONE).performClick()
        waitForIdle()

        assertTrue(
            screen.actions.any { it is PlayPokerAction.LeaveGameFromBust },
            "dismissing the match-over result must tear the session down",
        )
        assertEquals(1, screen.backCount(), "dismissing the match-over result must route off the table")
    }

    // ── Screen-local surfaces, reached through their real affordance ─────────

    /** The top-bar "?" is the only way into the coaching sheet. */
    @Test
    fun helpButton_opensHowToPlaySheet() = runComposeUiTest {
        render(PlayPokerState(table = table()))
        onNodeWithText(HOW_TO_PLAY_TITLE).assertDoesNotExist()

        onNodeWithContentDescription(HELP_A11Y).performClick()
        awaitText(HOW_TO_PLAY_TITLE)

        onNodeWithText(HOW_TO_PLAY_TITLE).assertIsDisplayed()
    }

    /** Nothing may auto-open the coaching sheet on an ordinary table. */
    @Test
    fun howToPlaySheet_absentUntilHelpTapped() = runComposeUiTest {
        render(PlayPokerState(table = table()))
        onNodeWithText(HOW_TO_PLAY_TITLE).assertDoesNotExist()
    }

    /** The ↑ on the bet bar is the only route to the full raise controls. */
    @Test
    fun moreRaiseOptions_opensActionSheet() = runComposeUiTest {
        render(PlayPokerState(table = table(isHumanTurn = true)))
        onNodeWithText(BET_PRESET_MIN).assertDoesNotExist()

        onNodeWithContentDescription(MORE_RAISE_OPTIONS_A11Y).performClick()
        awaitText(BET_PRESET_MIN)

        onNodeWithText(BET_PRESET_MIN).assertIsDisplayed()
    }

    /**
     * Off-turn there are no legal actions, so the affordance must be gone —
     * not merely inert. A visible-but-dead ↑ is the shape of MP-20.
     */
    @Test
    fun actionSheet_notOfferedOffTurn() = runComposeUiTest {
        render(PlayPokerState(table = table(isHumanTurn = false, actingSeatIndex = 1)))
        onNodeWithContentDescription(MORE_RAISE_OPTIONS_A11Y).assertDoesNotExist()
        onNodeWithText(BET_PRESET_MIN).assertDoesNotExist()
    }

    /**
     * product-spec.md §5.4: landing on a bot-stacked MP table must explain the
     * halved-XP cost before the player commits, never a silent downgrade.
     */
    @Test
    fun practiceTierTable_autoOpensExplainer() = runComposeUiTest {
        render(
            PlayPokerState(
                table = table(practiceTierBotsPresent = true, practiceTierBotsOnly = true),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        onNodeWithText(PRACTICE_TIER_TITLE).assertIsDisplayed()
    }

    /** An ordinary table must never pop the practice-tier explainer at the player. */
    @Test
    fun ordinaryTable_noPracticeTierExplainer() = runComposeUiTest {
        render(PlayPokerState(table = table()))
        onNodeWithText(PRACTICE_TIER_TITLE).assertDoesNotExist()
    }

    /**
     * Tap-an-opponent is the entry point for mute / report / style. Asserted
     * with `assertExists` rather than `assertIsDisplayed`: the sheet body is a
     * scroll container, so whether a given row is inside the viewport depends
     * on the harness's screen height, not on whether the sheet opened.
     */
    @Test
    fun opponentTap_opensPlayerProfileSheet() = runComposeUiTest {
        render(PlayPokerState(table = table()))
        onNodeWithText(BOT_CALLOUT_TITLE).assertDoesNotExist()

        tapOpponentAvatar()
        awaitText(BOT_CALLOUT_TITLE)

        onNodeWithText(BOT_CALLOUT_TITLE).assertExists()
    }

    // ── One blocking surface at a time ───────────────────────────────────────

    /**
     * The hand-result dialog owns the screen while it's up. Back must resolve
     * *it* and must not stack the leave-confirm behind/over it, and must not
     * silently leave the table either.
     */
    @Test
    fun handResultDialogUp_backResolvesTheHand_notTheLeaveFlow() = runComposeUiTest {
        val screen = render(PlayPokerState(table = finishedHand(humanWon = true)))
        onNodeWithText(SHOWDOWN_YOU_WIN).assertIsDisplayed()

        screen.pressSystemBack()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertDoesNotExist()
        assertFalse(
            screen.actions.any { it is PlayPokerAction.LeaveTable },
            "back on the hand-result dialog must not leave the table",
        )
        assertTrue(
            screen.actions.any { it is PlayPokerAction.RequestNextHand },
            "back on the hand-result dialog resolves the hand",
        )
    }

    /**
     * A mid-game joiner's first Active projection can already carry a finished
     * hand: the practice-tier auto-explainer fires on that same projection while
     * the result dialog renders from it. Two blocking surfaces, no coordination.
     *
     * This test records what the screen does today. See the report.
     */
    @Test
    fun practiceTierExplainer_yieldsToTheHandResultDialog() = runComposeUiTest {
        // A mid-game joiner (or a re-entry after process death) can land on a
        // projection that is *both* the first practice-tier table we've seen and
        // already carrying a handResult. Auto-opening the explainer there stacks
        // two modal scrims. The explainer is the one that waits: the result
        // dialog is the interactive one, and the explainer still auto-opens on
        // the next result-free projection because the guard hasn't been set.
        render(
            PlayPokerState(
                table = finishedHand(
                    humanWon = true,
                    practiceTierBotsPresent = true,
                    practiceTierBotsOnly = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )

        onNodeWithText(SHOWDOWN_YOU_WIN).assertIsDisplayed()
        onNodeWithText(PRACTICE_TIER_TITLE).assertDoesNotExist()
    }

    /**
     * The one stack that *is* deliberate (see the quick-buy comment on
     * `PlayPokerScreen`): the bust dialog stays mounted underneath so a
     * successful top-up returns the player straight to the rebuy decision.
     * Catches a "fix" that unmounts the bust dialog and drops them out of the
     * flow after buying.
     */
    @Test
    fun quickBuySheet_overlaysBustDialogWithoutUnmountingIt() = runComposeUiTest {
        render(
            PlayPokerState(
                table = finishedHand(humanWon = false, humanStack = 0),
                xpMode = XpMode.MULTIPLAYER,
                quickBuyOpen = true,
            ),
        )

        awaitText(QUICK_BUY_SUBTITLE)
        onNodeWithText(MP_BUST_TITLE).assertIsDisplayed()
        onNodeWithText(QUICK_BUY_SUBTITLE).assertIsDisplayed()
    }

    // ── Surfaces across a hand boundary ──────────────────────────────────────

    /**
     * The one force-close on this screen. `LaunchedEffect(isHumanTurn)` shuts
     * the raise sheet the moment the turn moves on — without it the player is
     * left holding a raise slider for a hand they can no longer act in, and a
     * submit from it would be rejected by the server.
     */
    @Test
    fun actionSheet_forceClosedWhenTheTurnPasses() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table(isHumanTurn = true)))
        onNodeWithContentDescription(MORE_RAISE_OPTIONS_A11Y).performClick()
        awaitText(BET_PRESET_MIN)

        screen.setTable(table(isHumanTurn = false, actingSeatIndex = 1))
        waitForIdle()

        onNodeWithText(BET_PRESET_MIN).assertDoesNotExist()
    }

    /** Same guard, across the deal — a new hand must not inherit the raise sheet. */
    @Test
    fun actionSheet_forceClosedAcrossAHandBoundary() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table(handNumber = 1, isHumanTurn = true)))
        onNodeWithContentDescription(MORE_RAISE_OPTIONS_A11Y).performClick()
        awaitText(BET_PRESET_MIN)

        // The hand ends: nobody is acting. Then a fresh hand deals.
        screen.setTable(finishedHand(humanWon = true))
        waitForIdle()
        screen.setTable(table(handNumber = 2, isHumanTurn = true, holeCards = secondHand()))
        waitForIdle()

        onNodeWithText(BET_PRESET_MIN).assertDoesNotExist()
    }

    /**
     * The gate is `isHumanTurn`, not the hand number. If the turn never flips
     * false across the deal, the sheet rides into the next hand. Pins the
     * current behaviour so a real fix (keying the effect on the hand too) is a
     * deliberate change rather than an accident. See the report.
     */
    @Test
    fun actionSheet_forceClosedOnANewHandEvenWhenTheTurnNeverPasses() = runComposeUiTest {
        // The sheet holds this hand's LegalActions. If the turn never flips
        // false across the deal, keying the force-close on isHumanTurn alone
        // rides hand 1's bet presets into hand 2 — you'd raise the wrong
        // amounts against the wrong stack. The hand number is the real key.
        val screen = render(PlayPokerState(table = table(handNumber = 1, isHumanTurn = true)))
        onNodeWithContentDescription(MORE_RAISE_OPTIONS_A11Y).performClick()
        awaitText(BET_PRESET_MIN)

        screen.setTable(table(handNumber = 2, isHumanTurn = true, holeCards = secondHand()))
        waitForIdle()

        onNodeWithText(BET_PRESET_MIN).assertDoesNotExist()
    }

    /**
     * The coaching sheet reads live table state (hand number, street, pot), so
     * surviving the deal is correct — it just re-renders for the new hand.
     * Catches a regression that keys it per hand and yanks it shut mid-read.
     */
    @Test
    fun howToPlaySheet_survivesAHandBoundary() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table(handNumber = 1)))
        onNodeWithContentDescription(HELP_A11Y).performClick()
        awaitText(HOW_TO_PLAY_TITLE)

        screen.setTable(table(handNumber = 2, holeCards = secondHand()))
        waitForIdle()

        onNodeWithText(HOW_TO_PLAY_TITLE).assertIsDisplayed()
    }

    /**
     * The auto-show guard is an unkeyed `remember`, which is the point: the
     * explainer is a once-per-session orientation, not a per-hand interruption.
     * Catches keying that guard on the hand and re-popping it every deal.
     */
    @Test
    fun practiceTierExplainer_doesNotReopenOnANewHand() = runComposeUiTest {
        val screen = render(
            PlayPokerState(
                table = table(handNumber = 1, practiceTierBotsPresent = true, practiceTierBotsOnly = true),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        onNodeWithText(PRACTICE_TIER_TITLE).assertIsDisplayed()

        screen.pressSystemBack()
        waitForIdle()
        onNodeWithText(PRACTICE_TIER_TITLE).assertDoesNotExist()

        screen.setTable(
            table(
                handNumber = 2,
                holeCards = secondHand(),
                practiceTierBotsPresent = true,
                practiceTierBotsOnly = true,
            ),
        )
        waitForIdle()

        onNodeWithText(PRACTICE_TIER_TITLE).assertDoesNotExist()
    }

    /**
     * `profileSheetSeat` stores the tapped [SeatView] by value, so the sheet
     * shows a frozen snapshot of that seat while the table moves on beneath it.
     * "At this table" is the visible symptom. Pins the current behaviour; see
     * the report.
     */
    @Test
    fun playerProfileSheet_resolvesTheSeatLive() = runComposeUiTest {
        // Storing the tapped SeatView by value freezes stack, tenure and last
        // move at the instant of the tap. Leave the sheet open across six hands
        // and it still reads "1 hand at this table". The sheet must resolve off
        // the live seats list, as the self card already does.
        val screen = render(PlayPokerState(table = table(handNumber = 1)))
        tapOpponentAvatar()
        awaitText(TENURE_ONE_HAND)

        screen.setTable(table(handNumber = 7, holeCards = secondHand()))
        waitForIdle()

        onNodeWithText(TENURE_SEVEN_HANDS).assertExists()
        onNodeWithText(TENURE_ONE_HAND).assertDoesNotExist()
    }

    /**
     * The leave-confirm quotes live numbers (cash-out, chips forfeited this
     * hand), so it must not be a stale snapshot when the deal moves under it.
     * Catches a regression that closes the dialog on the deal and silently
     * drops a leave the player had already decided on.
     */
    @Test
    fun leaveConfirm_survivesAHandBoundary() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table(handNumber = 1)))
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()
        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()

        screen.setTable(table(handNumber = 2, holeCards = secondHand()))
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()
    }

    // ── Leave flow ──────────────────────────────────────────────────────────

    /** A live hand costs something to abandon, so back must always confirm. */
    @Test
    fun handInProgress_backConfirms() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table(), xpMode = XpMode.BOTS))
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()
        assertTrue(screen.actions.isEmpty(), "confirming must not leave yet")
        assertEquals(0, screen.backCount())
    }

    /**
     * ROOM-4. On a real-chip seat the confirm has to name the money: the net
     * this leave settles, and the chips already committed to the live hand that
     * walking away forfeits. Catches a regression that drops the settlement copy
     * and lets a player abandon a posted blind without being told.
     */
    @Test
    fun realMoneyLeaveConfirm_namesTheChipsForfeitedThisHand() = runComposeUiTest {
        render(
            PlayPokerState(
                table = table(practiceTierBotsOnly = false),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()
        onNodeWithText(LEAVE_FORFEIT_NOTE, substring = true).assertExists()
    }

    /**
     * A real-money seat confirms even between hands — chips are sitting at the
     * table and a silent back-out settles them without the player seeing it.
     */
    @Test
    fun realMoneySeat_backConfirmsEvenBetweenHands() = runComposeUiTest {
        val screen = render(
            PlayPokerState(
                table = finishedHand(humanWon = true, practiceTierBotsOnly = false),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()
        assertTrue(screen.actions.isEmpty())
        assertEquals(0, screen.backCount())
    }

    /**
     * The counterpart to MP-31, and the only bots-only shape where the back
     * chevron is actually reachable between hands: the public disclosed-bot
     * subsidy table. Real chips ARE at stake there, but `practiceTierBotsOnly`
     * is true, so `requiresLeaveConfirmation` reads false and the exit is one
     * tap — the "leave with your winnings" north star. Catches a fix for MP-31
     * that over-corrects into confirming every MP exit and buries that.
     *
     * (On an ordinary practice table this path can't be reached at all: with a
     * hand result on the table the result dialog is modal over the chevron. See
     * the report.)
     */
    @Test
    fun subsidizedBotTable_backLeavesImmediatelyBetweenHands() = runComposeUiTest {
        val screen = render(
            PlayPokerState(
                table = finishedHand(
                    humanWon = true,
                    practiceTierBotsPresent = true,
                    practiceTierBotsOnly = true,
                    subsidizedBotTable = true,
                ),
                xpMode = XpMode.MULTIPLAYER,
            ),
        )
        // The practice-tier explainer stays out of the way here: this
        // projection already carries a hand result, and the explainer yields to
        // it rather than stacking a second scrim. So the back affordance is the
        // only thing between the player and the leave path.
        onNodeWithText(PRACTICE_TIER_TITLE).assertDoesNotExist()

        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertDoesNotExist()
        assertTrue(screen.actions.any { it is PlayPokerAction.LeaveTable })
        assertEquals(1, screen.backCount())
    }

    /**
     * MP-31. A stuck / degraded MP table never projects to Active, so
     * `isRealMultiplayer` reads false there — exactly the case where a silent
     * back-out can strand real chips at the seat. `requiresLeaveConfirmation`
     * uses `active?.practiceTierBotsOnly != true`, which is true when `active`
     * is null, on purpose. This test is the guard on that deliberate `!= true`.
     */
    @Test
    fun degradedMultiplayerTable_stillConfirms() = runComposeUiTest {
        val screen = render(
            PlayPokerState(table = TableUiState.Loading, xpMode = XpMode.MULTIPLAYER),
        )
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()
        assertTrue(screen.actions.isEmpty(), "MP-31: a degraded MP seat must not back out silently")
        assertEquals(0, screen.backCount())
    }

    /** "Stay" must be a true no-op — dialog gone, still seated. */
    @Test
    fun leaveConfirmStay_keepsTheSeat() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table()))
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_STAY).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertDoesNotExist()
        assertTrue(screen.actions.isEmpty(), "Stay must not fire a leave")
        assertEquals(0, screen.backCount())
    }

    /** "Leave" must both tear the session down and pop — one without the other strands. */
    @Test
    fun leaveConfirmLeave_tearsDownAndPops() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table()))
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_LEAVE).performClick()
        waitForIdle()

        assertTrue(screen.actions.any { it is PlayPokerAction.LeaveTable })
        assertEquals(1, screen.backCount())
    }

    /**
     * The gesture / hardware back must run the same gate as the chevron. A
     * `BackHandler` wired past the confirm is how a player swipes a real-money
     * seat away by accident.
     */
    @Test
    fun systemBack_runsTheSameLeaveGate() = runComposeUiTest {
        val screen = render(
            PlayPokerState(table = TableUiState.Loading, xpMode = XpMode.MULTIPLAYER),
        )
        screen.pressSystemBack()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertIsDisplayed()
        assertTrue(screen.actions.isEmpty())
        assertEquals(0, screen.backCount())
    }

    /**
     * The tutorial passes `confirmLeave = false` — no real hand, no XP, nothing
     * to forfeit. Catches the confirm creeping back into the guided flow.
     */
    @Test
    fun confirmLeaveDisabled_backLeavesWithoutPrompting() = runComposeUiTest {
        val screen = render(PlayPokerState(table = table()), confirmLeave = false)
        onNodeWithContentDescription(BACK_A11Y).performClick()
        waitForIdle()

        onNodeWithText(LEAVE_TITLE).assertDoesNotExist()
        assertTrue(screen.actions.any { it is PlayPokerAction.LeaveTable })
        assertEquals(1, screen.backCount())
    }


    // ── harness ─────────────────────────────────────────────────────────────

    /**
     * Renders the screen over hoisted state and hands back a driver that can
     * swap the table underneath it, dispatch a real back press, and read what
     * the screen asked the VM and the navigator to do.
     */
    private fun ComposeUiTest.render(
        initial: PlayPokerState,
        confirmLeave: Boolean = true,
    ): ScreenDriver {
        val actions = mutableListOf<PlayPokerAction>()
        var backCount = 0
        var state by mutableStateOf(initial)
        var dispatcher: OnBackPressedDispatcher? = null
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewContent {
                PlayPokerScreen(
                    state = state,
                    onAction = { actions += it },
                    onBack = { backCount += 1 },
                    confirmLeave = confirmLeave,
                )
            }
        }
        waitForIdle()
        return ScreenDriver(
            actions = actions,
            backCount = { backCount },
            setState = { state = it },
            currentState = { state },
            back = { requireNotNull(dispatcher).onBackPressed() },
        )
    }

    /**
     * Waits for [text] to compose. The DS bottom sheet is an M3
     * `ModalBottomSheet` in its own window, so it attaches and animates in a
     * beat after the tap that opened it; a bare `waitForIdle` can land before
     * its content is in the tree while the felt's own looping animations keep
     * the frame clock busy.
     */
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(conditionDescription = text, timeoutMillis = SURFACE_TIMEOUT_MS) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Taps the first bot's seat. Driven through the seat's own click semantics
     * rather than injected coordinates: the seat sits inside an animated
     * `graphicsLayer` scale and is clipped by the opponents row, so its
     * `boundsInRoot` is both transformed and smaller than its layout size, and
     * coordinate injection lands unreliably in this harness. The semantics
     * action is the accessibility click path and invokes the same
     * `Modifier.clickable` a finger would.
     */
    private fun ComposeUiTest.tapOpponentAvatar() {
        onNodeWithText(BOT_ONE_INITIAL).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private class ScreenDriver(
        val actions: List<PlayPokerAction>,
        val backCount: () -> Int,
        private val setState: (PlayPokerState) -> Unit,
        private val currentState: () -> PlayPokerState,
        private val back: () -> Unit,
    ) {
        /** Swaps the projected table, the way a fresh snapshot does in production. */
        fun setTable(table: TableUiState.Active) = setState(currentState().copy(table = table))

        fun pressSystemBack() = back()
    }

    private companion object {
        const val HUMAN_SEAT = 0

        /** Generous: a sheet attach + its enter animation, never a real wait. */
        const val SURFACE_TIMEOUT_MS = 5_000L

        // Copy, verbatim from libraries/resources/.../values/strings.xml.
        const val BACK_A11Y = "Back"
        const val HELP_A11Y = "Hand info and rankings"
        const val MORE_RAISE_OPTIONS_A11Y = "More raise options"
        const val RANKINGS_HEADING = "What beats what"
        const val HOW_TO_PLAY_TITLE = "How to play"
        const val BET_PRESET_MIN = "Min"
        const val QUICK_BUY_SUBTITLE = "Top up without leaving the table."
        const val MATCH_OVER_WON = "You won the match"
        const val MATCH_OVER_DONE = "Done"
        const val PRACTICE_TIER_TITLE = "Practice tier"
        const val BOT_CALLOUT_TITLE = "This is a bot"
        const val SHOWDOWN_YOU_WIN = "You win"
        const val MP_BUST_TITLE = "You're out of chips"
        const val LEAVE_TITLE = "Leave the table?"
        const val LEAVE_FORFEIT_NOTE = "already in this hand that you'll forfeit by leaving now"
        const val LEAVE_STAY = "Stay"
        const val LEAVE_LEAVE = "Leave"
        const val TENURE_ONE_HAND = "1 hand at this table"
        const val TENURE_SEVEN_HANDS = "7 hands at this table"

        /**
         * The first bot's avatar initial. The avatar renders it as real text and
         * the tap detector sits on an ancestor, so clicking the glyph is the
         * opponent tap — no test tag needed. Names are picked so their initials
         * can't collide with a card rank or a blind marker on the felt.
         */
        const val BOT_ONE_INITIAL = "Z"

        fun firstHand(): List<Card> = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades))

        fun secondHand(): List<Card> = listOf(Card(Rank.Four, Suit.Hearts), Card(Rank.Three, Suit.Clubs))

        fun humanSeat(stack: Long, isActing: Boolean, holeCards: List<Card>, handsAtTable: Int): SeatView =
            SeatView(
                index = HUMAN_SEAT,
                displayName = "You",
                stack = stack,
                contributedThisStreet = 0,
                contributedThisHand = 20,
                isActing = isActing,
                isHuman = true,
                isBot = false,
                avatarKey = null,
                emoji = null,
                holeCards = holeCards,
                showHoleCardBacks = false,
                participation = HandParticipation.InHand,
                seatEmpty = false,
                isBusted = stack <= 0L,
                lastAction = null,
                isDealer = true,
                isSmallBlind = false,
                isBigBlind = false,
                handsAtTable = handsAtTable,
            )

        fun botSeat(index: Int, name: String, isActing: Boolean, handsAtTable: Int): SeatView = SeatView(
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
            handsAtTable = handsAtTable,
        )

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

        /**
         * A live hand, built the way `TableUiState.fromGameState` emits one:
         * `handsAtTable` tracks the hand number, and the human's legal actions
         * exist only on their turn.
         */
        fun table(
            handNumber: Int = 1,
            isHumanTurn: Boolean = true,
            actingSeatIndex: Int? = HUMAN_SEAT,
            street: BettingRound = BettingRound.Preflop,
            communityCards: List<Card> = emptyList(),
            handResult: HandResultView? = null,
            humanStack: Long = 980,
            holeCards: List<Card> = firstHand(),
            practiceTierBotsPresent: Boolean = false,
            practiceTierBotsOnly: Boolean = false,
            subsidizedBotTable: Boolean = false,
        ): TableUiState.Active = TableUiState.Active(
            street = street,
            communityCards = communityCards,
            pot = 30,
            potCommittedThisStreet = 0,
            seats = listOf(
                humanSeat(
                    stack = humanStack,
                    isActing = actingSeatIndex == HUMAN_SEAT,
                    holeCards = holeCards,
                    handsAtTable = handNumber,
                ),
                botSeat(index = 1, name = "Zed", isActing = actingSeatIndex == 1, handsAtTable = handNumber),
                botSeat(index = 2, name = "Quill", isActing = actingSeatIndex == 2, handsAtTable = handNumber),
            ),
            actingSeatIndex = actingSeatIndex,
            isHumanTurn = isHumanTurn,
            humanLegalActions = if (isHumanTurn) legalActions() else null,
            humanHandLabel = null,
            handResult = handResult,
            smallBlind = 10,
            bigBlind = 20,
            handNumber = handNumber,
            buttonSeatIndex = HUMAN_SEAT,
            smallBlindSeatIndex = 1,
            bigBlindSeatIndex = 2,
            practiceTierBotsPresent = practiceTierBotsPresent,
            practiceTierBotsOnly = practiceTierBotsOnly,
            subsidizedBotTable = subsidizedBotTable,
        )

        /**
         * A hand that has resolved at showdown — nobody acting, a result on the
         * table. This is the "between hands" shape the leave gate cares about.
         */
        fun finishedHand(
            humanWon: Boolean,
            handNumber: Int = 1,
            humanStack: Long = 1_180,
            practiceTierBotsPresent: Boolean = false,
            practiceTierBotsOnly: Boolean = false,
            subsidizedBotTable: Boolean = false,
        ): TableUiState.Active {
            val board = listOf(
                Card(Rank.Two, Suit.Clubs),
                Card(Rank.Seven, Suit.Diamonds),
                Card(Rank.Ten, Suit.Hearts),
                Card(Rank.Jack, Suit.Spades),
                Card(Rank.Queen, Suit.Clubs),
            )
            return table(
                handNumber = handNumber,
                isHumanTurn = false,
                actingSeatIndex = null,
                street = BettingRound.Showdown,
                communityCards = board,
                humanStack = humanStack,
                practiceTierBotsPresent = practiceTierBotsPresent,
                practiceTierBotsOnly = practiceTierBotsOnly,
                subsidizedBotTable = subsidizedBotTable,
                handResult = HandResultView(
                    winners = listOf(
                        HandWinner(
                            seatIndex = if (humanWon) HUMAN_SEAT else 1,
                            amount = 200,
                            handRank = null,
                            byFold = false,
                        ),
                    ),
                    board = board,
                ),
            )
        }
    }
}
