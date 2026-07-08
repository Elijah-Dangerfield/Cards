package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.LocalBotsSession

import com.dangerfield.cards.libraries.gameplay.BettingRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proof scenarios for the behaviour-driven harness — each drives a real
 * [PlayPokerViewModel] over a real [LocalBotsSession] with scripted bot actions
 * and asserts on [TableUiState] (what the player sees). These three exercise
 * the harness's core capabilities: scripted opponent actions, hand-result
 * dialogs, and stacked decks. The broad suite builds on them in Phase 2.
 *
 * Table is heads-up with Default blinds (SB 5 / BB 10). The human sits at seat 0
 * (the button/SB heads-up, so they act first preflop); the bot is seat 1 (BB).
 */
class PokerScenarioSoloTest : PokerScenarioTest() {

    @Test
    fun opponentRaisesPreflop_humanFacesCallAndSeesRaisePill() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { raisesTo(30) }
            .start()

        // Human (SB) acts first preflop; completing the blind hands action to
        // the BB bot, which executes its scripted raise to 30.
        s.iCall()

        assertTable(s.table) {
            isHumanTurn(true)
            humanCanCall(20) // 30 (current bet) − 10 (already in) = 20 to call
            seatPill(1, "Raised to 30")
        }
    }

    @Test
    fun humanFoldsPreflop_handEnds_opponentWinsByFold() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .start()

        s.iFold()

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultShowing()
            handResultWinner(seat = 1) // BB wins uncontested
            seatFolded(seat = 0)
        }
    }

    @Test
    fun stackedBoard_humanFlopsPair_handLabelReflectsIt() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .deal("Ah Kh", "Qs Qd") // seat 0 = human, seat 1 = bot
            .board("Ad 7c 2s") // flop pairs the human's ace
            .scriptOpponent(1) { checks(); checks() } // BB checks preflop, then the flop
            .start()

        s.iCall() // complete the blind → bot checks → flop → bot checks → human to act

        assertTable(s.table) {
            street(BettingRound.Flop)
            humanHandLabel("Pair")
        }
    }

    @Test
    fun earlyBotFold_staysLegible_afterTheStreetAdvances() = runUnitTest {
        // GAME-17: seat 3 (UTG, 4-handed) folds as the very first action of the
        // hand. The fold must still read on the seat AND on the tapped player
        // card after the flop deals — clearing it with the street's pills left
        // the user staring at grey cards with no explanation (CARDS-8H).
        val s = soloScenario()
            .seats(4)
            .scriptOpponent(1) { calls(); checks() }
            .scriptOpponent(2) { checks(); checks() }
            .scriptOpponent(3) { folds() }
            .start()

        // Preflop: UTG (3) folds, human (button) calls, SB calls, BB checks →
        // flop deals; SB and BB check the flop, action back on the human.
        s.iCall()

        assertTable(s.table) {
            street(BettingRound.Flop)
            seatFolded(3)
            seatPill(3, "Folded")
        }
    }

    @Test
    fun humanFolds_recordsASessionLoss_overflowSheetReadsOneLoss() = runUnitTest {
        val s = soloScenario().seats(2).start()

        s.iFold()

        assertEquals(0, s.vm.state.sessionHandsWon, "a fold isn't a win")
        assertEquals(1, s.vm.state.sessionHandsLost, "the played hand counts as a session loss")
    }

    @Test
    fun humanWinsWhenOpponentFolds_recordsASessionWin() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { folds() }
            .start()

        // Human (SB) raises; the BB bot folds to it → the human takes the pot.
        s.iRaiseTo(30)

        assertEquals(1, s.vm.state.sessionHandsWon, "winning the pot counts as a session win")
        assertEquals(0, s.vm.state.sessionHandsLost)
    }
}
