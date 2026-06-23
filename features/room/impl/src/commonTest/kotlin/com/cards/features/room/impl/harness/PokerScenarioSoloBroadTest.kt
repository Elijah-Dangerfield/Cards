package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import kotlin.test.Test

/**
 * The broad solo behaviour suite — scripted bot actions + stacked decks driving
 * a real VM, asserting on [TableUiState]. Heads-up, Default blinds (SB 5 / BB
 * 10); human is seat 0 (button/SB preflop), bot is seat 1 (BB).
 *
 * Heads-up turn order: preflop the SB/button (human) acts first; postflop the
 * BB (bot) acts first. The scripted bot's actions below are enqueued in that
 * play order.
 */
class PokerScenarioSoloBroadTest : PokerScenarioTest() {

    @Test
    fun humanRaises_opponentFolds_humanWins_andPillClearsNextHand() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { folds(); calls() } // fold to the raise (h1); call as SB next hand (h2)
            .start()

        s.iRaiseTo(30)

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultWinner(seat = 0)
            seatPill(1, "Folded")
        }

        s.iRequestNextHand()

        assertTable(s.table) {
            handNumber(2)
            noHandResult()
        }
    }

    @Test
    fun checkedDownShowdown_humanWinsTwoPair_holesRevealed() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .deal("Ah Kh", "Qs Qd") // human two pair (A,K) vs bot pair Q
            .board("Ad Kd 7c 2s 9h")
            .scriptOpponent(1) { checks(); checks(); checks(); checks() }
            .start()

        s.iCall() // preflop
        s.iCheck() // flop
        s.iCheck() // turn
        s.iCheck() // river → showdown

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultWinner(seat = 0)
            seatHoleCardsVisible(seat = 1) // opponent's cards revealed at showdown
        }
    }

    @Test
    fun checkedDownShowdown_opponentWins_whenBoardFavoursThem() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .deal("Qs Qd", "Ah Kh") // bot two pair (A,K) vs human pair Q
            .board("Ad Kd 7c 2s 9h")
            .scriptOpponent(1) { checks(); checks(); checks(); checks() }
            .start()

        s.iCall()
        s.iCheck()
        s.iCheck()
        s.iCheck()

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultWinner(seat = 1)
        }
    }

    @Test
    fun allInPreflop_calledAndRunOut_loserSeatIsBusted() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .deal("Ah Ad", "Kh Kc") // human trips vs bot pair → human wins
            .board("Ac 7c 2s 9h 3d")
            .scriptOpponent(1) { calls() } // call the all-in
            .start()

        s.iAllIn()

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultWinner(seat = 0)
            seatBusted(seat = 1)
        }
    }

    @Test
    fun opponentBetsFlop_humanCannotCheck_seesBetPillAndCallAmount() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { checks(); bets(50) } // check preflop, lead the flop
            .start()

        s.iCall() // → flop, bot leads 50, action back to human

        assertTable(s.table) {
            street(BettingRound.Flop)
            humanCannotCheck()
            humanCanCall(50)
            seatPill(1, "Bet 50")
        }
    }

    @Test
    fun multiStreetFold_handResultNullUntilHumanFoldsTurn() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { checks(); checks(); bets(50) } // preflop, flop, then bet turn
            .start()

        s.iCall() // → flop
        assertTable(s.table) {
            street(BettingRound.Flop)
            noHandResult()
        }

        s.iCheck() // → turn, bot bets 50
        assertTable(s.table) {
            street(BettingRound.Turn)
            humanCannotCheck()
            seatPill(1, "Bet 50")
            noHandResult()
        }

        s.iFold()
        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultWinner(seat = 1)
            seatFolded(seat = 0)
        }
    }

    @Test
    fun humanBetsFlop_opponentFolds_humanWinsUncontested() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { checks(); checks(); folds() } // preflop check, flop check, fold to the bet
            .start()

        s.iCall() // → flop, bot checks, action to human
        assertTable(s.table) { street(BettingRound.Flop) }

        s.iBet(50) // bot folds to it
        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultWinner(seat = 0)
            seatFolded(seat = 1)
        }
    }

    @Test
    fun requestNextHand_clearsHandResult_andIncrementsHandNumber() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { calls() } // bot is SB/first next hand → call to stop on the human
            .start()

        s.iFold() // hand 1: human SB folds, bot wins
        assertTable(s.table) {
            handNumber(1)
            handResultShowing()
        }

        s.iRequestNextHand()
        assertTable(s.table) {
            handNumber(2)
            noHandResult()
        }
    }
}
