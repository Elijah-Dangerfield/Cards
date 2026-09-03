package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.MatchOverResult
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.features.room.impl.session.MatchOverCountdown
import com.dangerfield.cards.features.room.impl.session.NextHandCountdown
import com.dangerfield.cards.libraries.cards.EarnedAchievement
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
 * The end-of-hand disposition tree of [PlayPokerScreen] — which of the four
 * mutually exclusive surfaces a finished hand puts in front of the player:
 *
 * ```
 * winner waiting on the rebuy grace -> no dialog, countdown banner only (MP-22)
 * bust on a real-chips MP seat      -> MultiplayerBustDialog (rebuy / buy / leave)
 * bust anywhere else                -> showdown reveal (GAME-18) then BustDialog
 * no bust, no real chips at stake   -> ShowdownDialog
 * no bust, real chips at stake      -> NO dialog: the felt countdown + cash out
 * ```
 *
 * Every branch decides something the player can lose money or progress over, and
 * neighbouring branches differ by one boolean, so a wrong turn is silent: the
 * practice bust copy on a real-chip seat promises a free refill that isn't
 * coming, and a modal on a real-chip table buries the leave-with-winnings window
 * the whole feature exists for.
 *
 * Conventions follow [PlayPokerScreenTest]: assert the state-keyed chrome the
 * screen owns — dialog titles, CTA labels, banners, the presence or absence of a
 * dialog — never the DS internals underneath. Copy is quoted from
 * `libraries/resources/.../strings.xml`. States are built through shared
 * builders in the production shapes the projection actually emits
 * ([realMpState], [practiceMpState], [subsidizedState], [soloState]), so no test
 * leans on a flag combination [TableUiState.Active] can't be projected into.
 *
 * Tests that cross a dismissal hoist the state, as in
 * [PlayPokerScreenMultiHandTest] — the interesting half of this tree is what the
 * *next* state does with a tap the previous one recorded.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class PlayPokerScreenHandEndTest {

    // ── real-chips multiplayer bust: MultiplayerBustDialog ───────────────────

    /**
     * The branch split that matters most in this tree. Routing a real-chips bust
     * to the practice dialog would tell a player who just lost real chips that
     * they've been refilled and can deal themselves back in for free.
     */
    @Test
    fun realMpBust_showsTheRealChipsDialog_notThePracticeRebuyCopy() = runComposeUiTest {
        renderScreen(realMpState(humanStack = 0))

        onNodeWithText(MP_BUST_TITLE).assertIsDisplayed()
        onNodeWithText(BUST_TITLE).assertDoesNotExist()
        onNodeWithText(DEAL_ME_IN).assertDoesNotExist()
    }

    /** Wallet covers the buy-in, so the primary CTA is the one-tap rebuy. */
    @Test
    fun realMpBust_walletCoversTheBuyIn_offersRebuy() = runComposeUiTest {
        renderScreen(realMpState(humanStack = 0, buyIn = 1_000).copy(chipBalance = 8_400))

        onNodeWithText(REBUY_CTA).assertIsDisplayed()
        onNodeWithText(BUY_CHIPS).assertDoesNotExist()
    }

    /**
     * Wallet is short. Offering "Rebuy" here sends the player into a server
     * refusal instead of the top-up that would actually get them seated.
     */
    @Test
    fun realMpBust_walletIsShort_offersBuyChipsInstead() = runComposeUiTest {
        renderScreen(realMpState(humanStack = 0, buyIn = 1_000).copy(chipBalance = 120))

        onNodeWithText(BUY_CHIPS).assertIsDisplayed()
        onNodeWithText(REBUY_CTA).assertDoesNotExist()
    }

    /**
     * Balance is null until the first wallet sync lands. An unknown balance is
     * not an affordable one — the gate is `chipBalance != null && >= buyIn`, and
     * dropping the null half offers a rebuy nobody has confirmed they can pay.
     */
    @Test
    fun realMpBust_balanceNotYetSynced_neverOffersRebuy() = runComposeUiTest {
        renderScreen(realMpState(humanStack = 0, buyIn = 1_000).copy(chipBalance = null))

        onNodeWithText(BUY_CHIPS).assertIsDisplayed()
        onNodeWithText(REBUY_CTA).assertDoesNotExist()
    }

    /**
     * MP-38: a rebuy round-trip used to look like a dead tap, so players tapped
     * again and one real rebuy averaged thirteen doomed duplicate intents. While
     * it's in flight the CTA states it's working and neither button fires.
     */
    @Test
    fun realMpBust_rebuyInFlight_showsProgressAndBlocksRepeatTaps() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        renderScreen(
            realMpState(humanStack = 0, buyIn = 1_000)
                .copy(chipBalance = 8_400, rebuyInFlight = true),
            onAction = { actions += it },
        )

        onNodeWithText(REBUY_PENDING).assertIsDisplayed()
        onNodeWithText(REBUY_CTA).assertDoesNotExist()

        onNodeWithText(REBUY_PENDING).performClick()
        onNodeWithText(LEAVE_GAME).performClick()
        waitForIdle()

        assertFalse(PlayPokerAction.Rebuy in actions, "a second rebuy must not be submitted")
        assertFalse(
            PlayPokerAction.LeaveGameFromBust in actions,
            "leaving mid-rebuy would race the seat refill",
        )
    }

    /** The rebuy CTA submits a rebuy, not a next-hand request. */
    @Test
    fun realMpBust_rebuyTap_submitsARebuy() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        renderScreen(
            realMpState(humanStack = 0, buyIn = 1_000).copy(chipBalance = 8_400),
            onAction = { actions += it },
        )

        onNodeWithText(REBUY_CTA).performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.Rebuy in actions, "the rebuy CTA submits PlayPokerAction.Rebuy")
    }

    /**
     * Leaving from the bust dialog has to run the seat teardown that reconciles
     * the wallet, not just pop the screen — a plain back-out strands the chips
     * at the seat.
     */
    @Test
    fun realMpBust_leaveGame_tearsDownTheSeatAndRoutesBack() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        var routedBack = false
        renderScreen(
            realMpState(humanStack = 0, buyIn = 1_000).copy(chipBalance = 8_400),
            onAction = { actions += it },
            onBack = { routedBack = true },
        )

        onNodeWithText(LEAVE_GAME).performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.LeaveGameFromBust in actions, "leaving settles the seat")
        assertTrue(routedBack, "leaving routes off the table")
    }

    /**
     * The GAME-18 reveal-then-bust sequence is deliberately solo-only: on a real
     * seat the recovery decision (rebuy or leave, with money on it) comes first.
     * Generalising the reveal to MP would put a "Continue" step in front of it.
     */
    @Test
    fun realMpBust_atShowdown_goesStraightToTheRecoveryDecision() = runComposeUiTest {
        renderScreen(realMpState(humanStack = 0))

        onNodeWithText(MP_BUST_TITLE).assertIsDisplayed()
        onNodeWithText(REVEAL_CONTINUE).assertDoesNotExist()
    }

    // ── solo / practice bust: reveal then BustDialog ─────────────────────────

    /**
     * The reveal already showed the XP for this hand, and the bust dialog passes
     * `null` when it followed one. Re-showing it reads as a second award for the
     * same hand.
     */
    @Test
    fun soloBustAtShowdown_doesNotRepeatTheXpBubbleOnTheBustDialog() = runComposeUiTest {
        renderScreen(soloState(humanStack = 0).copy(lastHandXpAwarded = 40))

        onNodeWithText(XP_EARNED).assertIsDisplayed()

        onNodeWithText(REVEAL_CONTINUE).performClick()
        waitForIdle()

        onNodeWithText(BUST_TITLE).assertIsDisplayed()
        onNodeWithText(XP_EARNED).assertDoesNotExist()
    }

    /**
     * `bustRevealAcknowledged` is remembered against the hand number so the gate
     * re-arms every hand. Drop that key and the reveal plays exactly once per
     * session: every later bust jumps straight to "You went bust" and the player
     * never sees the hand that took their stack.
     */
    @Test
    fun bustRevealGate_reArmsOnTheNextHand() = runComposeUiTest {
        val setState = renderHoisted(soloState(humanStack = 0, handNumber = 1))

        onNodeWithText(SHOWDOWN_HEADLINE).assertIsDisplayed()
        onNodeWithText(REVEAL_CONTINUE).performClick()
        waitForIdle()
        onNodeWithText(BUST_TITLE).assertIsDisplayed()

        setState(soloState(humanStack = 0, handNumber = 2))

        onNodeWithText(SHOWDOWN_HEADLINE).assertIsDisplayed()
        onNodeWithText(BUST_TITLE).assertDoesNotExist()
    }

    /**
     * The public disclosed-bot table busts through the same dialog as practice,
     * but the chips there are real and house-funded. The practice body ("chips
     * against bots don't count for keeps") would be a false statement about a
     * player's actual balance.
     */
    @Test
    fun subsidizedBotTableBust_saysTheChipsAreReal() = runComposeUiTest {
        renderScreen(subsidizedState(humanStack = 0, byFold = true))

        onNodeWithText(BUST_TITLE).assertIsDisplayed()
        onNodeWithText(BUST_SUBSIDIZED_BODY, substring = true).assertIsDisplayed()
        onNodeWithText(BUST_PRACTICE_BODY, substring = true).assertDoesNotExist()
    }

    // ── practice hand end: ShowdownDialog ────────────────────────────────────

    /**
     * The practice table's whole hand-end affordance is this modal. Losing it
     * leaves a finished solo hand with no way to deal the next one — solo has no
     * server-held auto-advance to fall back on.
     */
    @Test
    fun practiceHandEnd_showsTheResultDialogWithTheNextHandCta() = runComposeUiTest {
        renderScreen(soloState(humanStack = 1_200, humanWins = true))

        onNodeWithText(WIN_HEADLINE).assertIsDisplayed()
        // A full showdown summary is taller than a small screen, so the CTA
        // lives below the fold in the dialog's own scroll container.
        onNodeWithText(NEXT_HAND).performScrollTo().assertIsDisplayed()
    }

    /** With nothing pending and nothing earned, the CTA deals the next hand. */
    @Test
    fun practiceNextHandTap_requestsTheNextHand() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        renderScreen(
            soloState(humanStack = 1_200, humanWins = true, byFold = true),
            onAction = { actions += it },
        )

        onNodeWithText(NEXT_HAND).performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.RequestNextHand in actions)
    }

    // ── real chips, no bust: no dialog, felt countdown ───────────────────────

    /**
     * The north-star window. A modal here covers the felt while the server holds
     * the deal, so the player can't see their winnings land and can't cash out
     * before the next hand takes them.
     */
    @Test
    fun realChipsWin_replacesTheDialogWithTheFeltCountdown() = runComposeUiTest {
        renderScreen(
            realMpState(humanStack = 2_400, humanWins = true)
                .copy(nextHandCountdown = NextHandCountdown(deadlineEpochMs = ELAPSED_DEADLINE)),
        )

        onNodeWithText(NEXT_HAND_COUNTDOWN, substring = true).assertIsDisplayed()
        onNodeWithText(LEAVE_WITH_WINNINGS).assertIsDisplayed()
        onNodeWithText(WIN_HEADLINE).assertDoesNotExist()
        onNodeWithText(NEXT_HAND).assertDoesNotExist()
    }

    /**
     * Cashing out during the window leaves for real — and without the
     * leave-confirmation detour, which exists for abandoning a live hand, not for
     * the sanctioned between-hands exit.
     */
    @Test
    fun realChipsWin_leaveWithWinnings_leavesTheTable() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        var routedBack = false
        renderScreen(
            realMpState(humanStack = 2_400, humanWins = true)
                .copy(nextHandCountdown = NextHandCountdown(deadlineEpochMs = ELAPSED_DEADLINE)),
            onAction = { actions += it },
            onBack = { routedBack = true },
        )

        onNodeWithText(LEAVE_WITH_WINNINGS).performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.LeaveTable in actions, "cashing out leaves the table")
        assertTrue(routedBack, "cashing out routes off the table")
    }

    /**
     * The felt path is gated on `realChipsAtStake`, not `isRealMultiplayer`. The
     * disclosed-bot subsidy table is bots-only — so *not* real multiplayer — yet
     * pays real chips, and gating on the wrong flag drops it back to the modal
     * and takes its cash-out window with it.
     */
    @Test
    fun subsidizedBotTableWin_alsoSuppressesTheDialog() = runComposeUiTest {
        renderScreen(
            subsidizedState(humanStack = 2_400, humanWins = true)
                .copy(nextHandCountdown = NextHandCountdown(deadlineEpochMs = ELAPSED_DEADLINE)),
        )

        onNodeWithText(LEAVE_WITH_WINNINGS).assertIsDisplayed()
        onNodeWithText(WIN_HEADLINE).assertDoesNotExist()
        onNodeWithText(NEXT_HAND).assertDoesNotExist()
    }

    // ── MP-22: the heads-up rebuy grace ──────────────────────────────────────

    /**
     * MP-22. While the busted opponent's grace window is open the table cannot
     * deal, so the winner must not be handed a "Next hand" the server can only
     * refuse — the banner already tells them it auto-continues.
     *
     * Built on the practice-tier table on purpose: that's the shape where a
     * result dialog would otherwise render, so the suppression is the only thing
     * keeping the CTA off screen. (One human plus a bot, heads-up, the bot busts
     * — bots-only is exactly when `practiceTierBotsOnly` is set, and the grace
     * window is opened by the server for whichever seat busted.)
     */
    @Test
    fun winnerWaitingOnRebuyGrace_seesOnlyTheCountdownBanner() = runComposeUiTest {
        renderScreen(
            practiceMpState(humanStack = 2_400, humanWins = true).copy(
                matchOverCountdown = MatchOverCountdown(
                    deadlineEpochMs = ELAPSED_DEADLINE,
                    localPlayerIsBusted = false,
                ),
            ),
        )

        onNodeWithText(MATCH_OVER_WINNER_BANNER, substring = true).assertIsDisplayed()
        onNodeWithText(NEXT_HAND).assertDoesNotExist()
        onNodeWithText(WIN_HEADLINE).assertDoesNotExist()
    }

    /**
     * The other half of MP-22: the suppression is keyed on *who* busted. Widen it
     * to "any open grace window" and the busted player loses the dialog holding
     * their rebuy and leave decisions — the only two things they can do.
     */
    @Test
    fun bustedPlayerInRebuyGrace_stillGetsTheBustDialog() = runComposeUiTest {
        renderScreen(
            realMpState(humanStack = 0, buyIn = 1_000).copy(
                chipBalance = 8_400,
                matchOverCountdown = MatchOverCountdown(
                    deadlineEpochMs = ELAPSED_DEADLINE,
                    localPlayerIsBusted = true,
                ),
            ),
        )

        onNodeWithText(MP_BUST_TITLE).assertIsDisplayed()
        onNodeWithText(MATCH_OVER_BUSTED_REBUY).assertIsDisplayed()
    }

    // ── dismissal: achievements vs. dealing on ───────────────────────────────

    /**
     * Bot mode sequences unlocks after the result dialog rather than cramming
     * them into it. Dealing straight on would bury the celebration under the
     * next deal, and leaving the dialog mounted would stack it under the sheet.
     */
    @Test
    fun botsWithUnlocks_dismissOpensTheCelebrationInsteadOfDealing() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        renderScreen(
            soloState(humanStack = 1_200, humanWins = true, byFold = true)
                .copy(recentlyEarned = listOf(unlock())),
            onAction = { actions += it },
        )

        onNodeWithText(NEXT_HAND).performClick()
        waitForIdle()

        onNodeWithText(CELEBRATION_TITLE).assertIsDisplayed()
        // The sheet names the unlock it was handed, not just any unlock. (It
        // carries the name twice — sticky subtitle and card — so match the first.)
        onAllNodesWithText(UNLOCK_NAME)[0].assertIsDisplayed()
        onNodeWithText(NEXT_HAND).assertDoesNotExist()
        assertFalse(
            PlayPokerAction.RequestNextHand in actions,
            "the next hand waits until the celebration is dismissed",
        )
    }

    /** Dismissing the celebration is what finally deals the next hand. */
    @Test
    fun celebrationContinue_dealsTheNextHand() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        renderScreen(
            soloState(humanStack = 1_200, humanWins = true, byFold = true)
                .copy(recentlyEarned = listOf(unlock())),
            onAction = { actions += it },
        )

        onNodeWithText(NEXT_HAND).performClick()
        waitForIdle()
        onNodeWithText(CELEBRATION_CONTINUE).performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.CelebrationDismissed in actions)
        assertTrue(PlayPokerAction.RequestNextHand in actions)
    }

    /**
     * The `advanceRequested` hold. Achievement recording is async, so a fast tap
     * lands while `recentlyEarned` is still empty — without the hold that tap
     * falls through to "deal the next hand" and the unlock is never celebrated
     * at all. The hold has to survive the tap and resolve into the reveal.
     */
    @Test
    fun fastTapWhileComputing_stillRevealsTheCelebration() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        val pending = soloState(humanStack = 1_200, humanWins = true, byFold = true)
            .copy(awaitingHandEndAchievements = true)
        val setState = renderHoisted(pending, onAction = { actions += it })

        onNodeWithText(NEXT_HAND).performClick()
        waitForIdle()
        assertFalse(
            PlayPokerAction.RequestNextHand in actions,
            "the advance is held while recording is in flight",
        )

        setState(
            pending.copy(awaitingHandEndAchievements = false, recentlyEarned = listOf(unlock())),
        )

        onNodeWithText(CELEBRATION_TITLE).assertIsDisplayed()
        assertFalse(
            PlayPokerAction.RequestNextHand in actions,
            "the held tap reveals the unlock rather than skipping it",
        )
    }

    /**
     * The complement, and the one that keeps the hold from becoming a hang: when
     * recording lands with nothing earned, the held tap has to turn into the
     * advance it originally meant. A hold that never resolves leaves the table
     * sitting on a finished hand with a CTA that already did nothing once.
     */
    @Test
    fun fastTapWhileComputing_dealsOnceNothingWasEarned() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        val pending = soloState(humanStack = 1_200, humanWins = true, byFold = true)
            .copy(awaitingHandEndAchievements = true)
        val setState = renderHoisted(pending, onAction = { actions += it })

        onNodeWithText(NEXT_HAND).performClick()
        waitForIdle()
        assertFalse(
            PlayPokerAction.RequestNextHand in actions,
            "the advance is held while recording is in flight",
        )

        setState(pending.copy(awaitingHandEndAchievements = false))

        assertEquals(
            1,
            actions.count { it is PlayPokerAction.RequestNextHand },
            "the held tap deals exactly one hand once recording lands empty",
        )
        onNodeWithText(CELEBRATION_TITLE).assertDoesNotExist()
    }

    /**
     * The full-bleed celebration sheet is the bot-mode treatment; multiplayer
     * keeps unlocks inline so a live table isn't paused by a second surface.
     * Drop the `isBots` guard and every MP unlock stops the game.
     *
     * Played out over two states rather than rendered straight into the result,
     * because the order matters: a practice-tier table auto-opens its explainer
     * on arrival, so the hand-result dialog has to be the *later* of the two
     * surfaces — which is also the only order a player can reach this from.
     */
    @Test
    fun multiplayerUnlocks_dismissDealsWithNoCelebrationSheet() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        val handEnd = practiceMpState(humanStack = 1_200, humanWins = true, byFold = true)
            .copy(recentlyEarned = listOf(unlock()))
        val setState = renderHoisted(handEnd.midHand(), onAction = { actions += it })

        setState(handEnd)

        // The MP dialog carries the unlock inline, which pushes its CTA into the
        // dialog's own scroll container on a small screen.
        onNodeWithText(NEXT_HAND).performScrollTo().performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.RequestNextHand in actions, "MP deals on without a detour")
        onNodeWithText(CELEBRATION_CONTINUE).assertDoesNotExist()
    }

    // ── terminal: the match itself ended ─────────────────────────────────────

    /**
     * MP-14. When the grace expires the table is dead, and this used to be a
     * silent pop. The result names the outcome and its only exit runs the seat
     * teardown.
     */
    @Test
    fun matchOverResolved_namesTheOutcomeAndRoutesOff() = runComposeUiTest {
        val actions = mutableListOf<PlayPokerAction>()
        var routedBack = false
        renderScreen(
            realMpState(humanStack = 2_400, humanWins = true)
                .copy(matchOverResult = MatchOverResult(localPlayerWon = true)),
            onAction = { actions += it },
            onBack = { routedBack = true },
        )

        onNodeWithText(MATCH_OVER_WIN_TITLE).assertIsDisplayed()

        onNodeWithText(MATCH_OVER_DONE).performClick()
        waitForIdle()

        assertTrue(PlayPokerAction.LeaveGameFromBust in actions, "the dead seat is torn down")
        assertTrue(routedBack)
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private fun ComposeUiTest.renderScreen(
        state: PlayPokerState,
        onAction: (PlayPokerAction) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        renderHoisted(state, onAction, onBack)
    }

    /**
     * Renders over hoisted state and hands back a setter, so a test can swap in
     * the state the VM would emit next — an achievement landing, a new hand —
     * without a real session. Returns after the recomposition settles.
     */
    private fun ComposeUiTest.renderHoisted(
        initial: PlayPokerState,
        onAction: (PlayPokerAction) -> Unit = {},
        onBack: () -> Unit = {},
    ): (PlayPokerState) -> Unit {
        var current by mutableStateOf(initial)
        setContent {
            PreviewContent {
                PlayPokerScreen(state = current, onAction = onAction, onBack = onBack)
            }
        }
        waitForIdle()
        return { next ->
            current = next
            waitForIdle()
        }
    }

    private companion object {
        const val HUMAN_SEAT = 0
        const val OPPONENT_SEAT = 1

        // Copy under test, from libraries/resources/.../strings.xml.
        const val MP_BUST_TITLE = "You're out of chips"
        const val BUY_CHIPS = "Buy chips"
        const val LEAVE_GAME = "Leave game"
        const val REBUY_CTA = "Rebuy (1,000)"
        const val REBUY_PENDING = "Buying back in…"
        const val BUST_TITLE = "You went bust"
        const val DEAL_ME_IN = "Deal me in"
        const val BUST_PRACTICE_BODY = "don't count for keeps"
        const val BUST_SUBSIDIZED_BODY = "Fresh stack on the house"
        const val SHOWDOWN_HEADLINE = "Showdown"
        const val WIN_HEADLINE = "You win"
        const val NEXT_HAND = "Next hand"
        const val REVEAL_CONTINUE = "Continue"
        const val CELEBRATION_TITLE = "Achievement unlocked"
        const val CELEBRATION_CONTINUE = "Continue"
        const val XP_EARNED = "+40 XP"
        const val NEXT_HAND_COUNTDOWN = "Next hand in"
        const val LEAVE_WITH_WINNINGS = "Leave with winnings"
        const val MATCH_OVER_WINNER_BANNER = "Opponent busted."
        const val MATCH_OVER_BUSTED_REBUY = "Rebuy now"
        const val MATCH_OVER_WIN_TITLE = "You won the match"
        const val MATCH_OVER_DONE = "Done"
        const val UNLOCK_NAME = "Pocket rockets"

        /**
         * Countdown deadlines are pinned in the past. The DS ticker polls the
         * wall clock, which the test clock can't advance, so a future deadline
         * would spin `waitForIdle` forever; an elapsed one settles on the first
         * frame at "0:00". These tests assert the countdown's copy and CTA, not
         * its digits.
         */
        const val ELAPSED_DEADLINE = 0L

        fun unlock(): EarnedAchievement = PreviewSamples.earnedAchievement(
            name = UNLOCK_NAME,
            description = "Win a hand with pocket aces.",
        )

        fun card(rank: Rank, suit: Suit): Card = Card(rank, suit)

        fun board(): List<Card> = listOf(
            card(Rank.Two, Suit.Clubs),
            card(Rank.Seven, Suit.Diamonds),
            card(Rank.Ten, Suit.Hearts),
            card(Rank.Jack, Suit.Spades),
            card(Rank.Queen, Suit.Clubs),
        )

        fun humanSeat(stack: Long): SeatView = SeatView(
            index = HUMAN_SEAT,
            userId = "local-user",
            displayName = "You",
            stack = stack,
            contributedThisStreet = 0,
            isActing = false,
            isHuman = true,
            isBot = false,
            avatarKey = null,
            emoji = null,
            holeCards = listOf(card(Rank.Ace, Suit.Spades), card(Rank.King, Suit.Spades)),
            showHoleCardBacks = false,
            participation = HandParticipation.InHand,
            seatEmpty = false,
            isBusted = stack <= 0,
            lastAction = null,
            isDealer = true,
            isSmallBlind = true,
            isBigBlind = false,
        )

        fun opponentSeat(isBot: Boolean): SeatView = SeatView(
            index = OPPONENT_SEAT,
            userId = if (isBot) "bot-1" else "remote-user",
            displayName = if (isBot) "David" else "Robin",
            stack = 2_000,
            contributedThisStreet = 0,
            isActing = false,
            isHuman = false,
            isBot = isBot,
            avatarKey = null,
            emoji = null,
            holeCards = listOf(card(Rank.King, Suit.Hearts), card(Rank.Queen, Suit.Hearts)),
            showHoleCardBacks = false,
            participation = HandParticipation.InHand,
            seatEmpty = false,
            isBusted = false,
            lastAction = null,
            isDealer = false,
            isSmallBlind = false,
            isBigBlind = true,
        )

        /**
         * A finished heads-up hand, projected the way `TableUiState.fromGameState`
         * emits one: no seat acting, no legal actions, and a [HandResultView]
         * whose `byFold` agrees with the street and the board (a fold-out ends
         * preflop with nothing dealt; a showdown carries the full board).
         */
        fun handEndTable(
            humanStack: Long,
            humanWins: Boolean,
            byFold: Boolean,
            handNumber: Int,
            opponentIsBot: Boolean,
            practiceTierBotsPresent: Boolean = false,
            practiceTierBotsOnly: Boolean = false,
            subsidizedBotTable: Boolean = false,
            buyIn: Long = 1_000,
        ): TableUiState.Active = TableUiState.Active(
            street = if (byFold) BettingRound.Preflop else BettingRound.Showdown,
            communityCards = if (byFold) emptyList() else board(),
            pot = 0,
            potCommittedThisStreet = 0,
            seats = listOf(humanSeat(stack = humanStack), opponentSeat(isBot = opponentIsBot)),
            actingSeatIndex = null,
            isHumanTurn = false,
            humanLegalActions = null,
            humanHandLabel = null,
            handResult = HandResultView(
                winners = listOf(
                    HandWinner(
                        seatIndex = if (humanWins) HUMAN_SEAT else OPPONENT_SEAT,
                        amount = 240,
                        handRank = null,
                        byFold = byFold,
                    ),
                ),
                board = if (byFold) emptyList() else board(),
            ),
            smallBlind = 10,
            bigBlind = 20,
            buyIn = buyIn,
            handNumber = handNumber,
            buttonSeatIndex = HUMAN_SEAT,
            smallBlindSeatIndex = HUMAN_SEAT,
            bigBlindSeatIndex = OPPONENT_SEAT,
            practiceTierBotsPresent = practiceTierBotsPresent,
            practiceTierBotsOnly = practiceTierBotsOnly,
            subsidizedBotTable = subsidizedBotTable,
        )

        /**
         * The same table one beat earlier, mid-hand: nothing resolved, an
         * opponent on the clock. Rendering this first and then swapping in the
         * finished state replays the order a player actually arrives in.
         */
        fun PlayPokerState.midHand(): PlayPokerState = copy(
            table = (table as TableUiState.Active).copy(
                street = BettingRound.Preflop,
                communityCards = emptyList(),
                handResult = null,
                actingSeatIndex = OPPONENT_SEAT,
            ),
        )

        /** Solo bots: no real chips, no practice-tier labelling — its own flow. */
        fun soloState(
            humanStack: Long,
            humanWins: Boolean = false,
            byFold: Boolean = false,
            handNumber: Int = 1,
        ): PlayPokerState = PlayPokerState(
            table = handEndTable(
                humanStack = humanStack,
                humanWins = humanWins,
                byFold = byFold,
                handNumber = handNumber,
                opponentIsBot = true,
            ),
            xpMode = XpMode.BOTS,
        )

        /** A human opponent on a multiplayer seat: real chips, real rebuy. */
        fun realMpState(
            humanStack: Long,
            humanWins: Boolean = false,
            byFold: Boolean = false,
            buyIn: Long = 1_000,
        ): PlayPokerState = PlayPokerState(
            table = handEndTable(
                humanStack = humanStack,
                humanWins = humanWins,
                byFold = byFold,
                handNumber = 1,
                opponentIsBot = false,
                buyIn = buyIn,
            ),
            xpMode = XpMode.MULTIPLAYER,
        )

        /**
         * Private practice tier, bots-only. `practiceTierBotsOnly` implies
         * `practiceTierBotsPresent` in the projection (`MultiplayerCredit`), so
         * both are set — which is also why the practice-tier explainer
         * auto-opens over these tables, exactly as it does in the app.
         */
        fun practiceMpState(
            humanStack: Long,
            humanWins: Boolean = false,
            byFold: Boolean = false,
        ): PlayPokerState = PlayPokerState(
            table = handEndTable(
                humanStack = humanStack,
                humanWins = humanWins,
                byFold = byFold,
                handNumber = 1,
                opponentIsBot = true,
                practiceTierBotsPresent = true,
                practiceTierBotsOnly = true,
            ),
            xpMode = XpMode.MULTIPLAYER,
        )

        /** The public disclosed-bot table: bots-only, but real chips at stake. */
        fun subsidizedState(
            humanStack: Long,
            humanWins: Boolean = false,
            byFold: Boolean = false,
        ): PlayPokerState = PlayPokerState(
            table = handEndTable(
                humanStack = humanStack,
                humanWins = humanWins,
                byFold = byFold,
                handNumber = 1,
                opponentIsBot = true,
                practiceTierBotsPresent = true,
                practiceTierBotsOnly = true,
                subsidizedBotTable = true,
            ),
            xpMode = XpMode.MULTIPLAYER,
        )
    }
}
