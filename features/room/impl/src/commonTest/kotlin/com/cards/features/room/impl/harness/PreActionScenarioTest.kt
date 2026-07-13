package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClientFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end behaviour for pre-action toggles (GAME-30): a real
 * [PlayPokerViewModel] arms a pre-action while waiting, and the armed intent
 * fires — or disarms — against the live table the moment the situation changes.
 * Solo covers the fire-on-arm resolve; MP covers the real "waiting → opponent
 * acts → auto-fires" turn arrival and the disarm-when-a-bet-lands rule, using
 * frame-level control the solo bot loop can't pause for.
 */
class PreActionScenarioTest : PokerScenarioTest() {

    // ---------- Solo: resolve + submit ----------

    @Test
    fun armedCheckFold_foldsWhenFacingABet_andClears() = runUnitTest {
        val s = soloScenario().seats(2).start()
        // The human (SB) is first to act preflop and owes the big blind, so
        // checking is off the table — Check/Fold resolves to a fold.
        assertTable(s.table) { isHumanTurn(true); humanCannotCheck() }

        s.arm(PreAction.CheckFold)

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultShowing()
            handResultWinner(seat = 1)
            seatFolded(seat = 0)
        }
        assertNull(s.vm.state.armedPreAction, "the armed pre-action clears once it fires")
    }

    @Test
    fun armedCheckAny_checksThroughToTheNextStreet() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .deal("2c 7d", "9h Th")
            .board("Ah Kd Qs 5c 3d")
            .scriptOpponent(1) { checks(); checks(); checks() } // BB checks preflop, flop, turn
            .start()

        s.iCall() // complete SB → BB checks → flop → BB checks flop → human to act
        assertTable(s.table) { street(BettingRound.Flop); isHumanTurn(true); humanCanCheck() }

        s.arm(PreAction.CheckAny)

        // The armed check fired (advancing past the flop), rather than folding —
        // the human is still in the hand and it's now the turn.
        assertTable(s.table) { street(BettingRound.Turn); isHumanTurn(true) }
        assertEquals(
            HandParticipation.InHand,
            s.table.seats.first { it.isHuman }.participation,
            "an armed check keeps the human in the hand",
        )
        assertNull(s.vm.state.armedPreAction, "the armed check fires once, then clears")
    }

    // ---------- MP: turn arrival + disarm ----------

    private val flop = cards("Ah Kd Qs")

    @Test
    fun armedCheckAny_firesCheck_whenTheOpponentActionBringsTheTurn() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, MP_LOCAL_USER), mpSeat(1, "opp")),
                actingSeatIndex = 1,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 0,
            ),
        )
        assertTable(s.table) { isHumanTurn(false) }

        s.arm(PreAction.CheckAny)

        // Opponent checks → action comes to the human with no bet to face.
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, MP_LOCAL_USER), mpSeat(1, "opp")),
                actingSeatIndex = 0,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 0,
                lastSequence = 1,
            ),
        )

        val submitted = s.sentFrames().filterIsInstance<ClientFrame.SubmitIntent>().lastOrNull()
        assertEquals(PlayerIntent.Check(0), submitted?.intent, "the armed check fires on turn arrival")
        assertNull(s.vm.state.armedPreAction)
    }

    @Test
    fun armedCheckFold_firesFold_whenTheTurnArrivesFacingABet() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, MP_LOCAL_USER), mpSeat(1, "opp")),
                actingSeatIndex = 1,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 0,
            ),
        )

        s.arm(PreAction.CheckFold)

        // Opponent bets → the turn arrives with a bet to face; Check/Fold folds.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, MP_LOCAL_USER, contributedThisStreet = 0),
                    mpSeat(1, "opp", stack = 900, contributedThisStreet = 100),
                ),
                actingSeatIndex = 0,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 100,
                lastSequence = 1,
            ),
        )

        val submitted = s.sentFrames().filterIsInstance<ClientFrame.SubmitIntent>().lastOrNull()
        assertEquals(PlayerIntent.Fold(0), submitted?.intent, "Check/Fold folds when a bet landed while waiting")
        assertNull(s.vm.state.armedPreAction)
    }

    @Test
    fun armedCheckAny_disarms_whenTheTurnArrivesFacingABet() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, MP_LOCAL_USER), mpSeat(1, "opp")),
                actingSeatIndex = 1,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 0,
            ),
        )

        s.arm(PreAction.CheckAny)

        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, MP_LOCAL_USER, contributedThisStreet = 0),
                    mpSeat(1, "opp", stack = 900, contributedThisStreet = 100),
                ),
                actingSeatIndex = 0,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 100,
                lastSequence = 1,
            ),
        )

        assertTrue(
            s.sentFrames().none { it is ClientFrame.SubmitIntent },
            "an armed check must never auto-submit when facing a bet",
        )
        assertNull(s.vm.state.armedPreAction, "facing a bet cancels the armed check")
        assertTable(s.table) { isHumanTurn(true); humanCannotCheck() }
    }

    @Test
    fun armedPreAction_doesNotSurviveIntoTheNextHand() = runUnitTest {
        val s = mpScenario().start()
        // Hand 1: waiting on the opponent, Check/Fold armed but never reached.
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, MP_LOCAL_USER), mpSeat(1, "opp")),
                actingSeatIndex = 1,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 0,
                handNumber = 1,
            ),
        )
        s.arm(PreAction.CheckFold)

        // A fresh hand deals with the human first to act facing the blind — a
        // stale Check/Fold would silently auto-fold hand 2. It must not.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, MP_LOCAL_USER, contributedThisStreet = 5),
                    mpSeat(1, "opp", contributedThisStreet = 10),
                ),
                actingSeatIndex = 0,
                street = BettingRound.Preflop,
                currentBetThisStreet = 10,
                handNumber = 2,
                lastSequence = 1,
            ),
        )

        assertNull(s.vm.state.armedPreAction, "an arm from the prior hand is retired on the new deal")
        assertTrue(
            s.sentFrames().none { it is ClientFrame.SubmitIntent },
            "no auto-action fires on the fresh hand",
        )
        assertTable(s.table) { isHumanTurn(true); humanCannotCheck() }
    }

    @Test
    fun armedCheckAny_disarmsBeforeYourTurn_onceABetIsOut() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, MP_LOCAL_USER), mpSeat(1, "opp1"), mpSeat(2, "opp2")),
                actingSeatIndex = 1,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 0,
            ),
        )

        s.arm(PreAction.CheckAny)

        // opp1 bets → action moves to opp2; the human now faces a bet but it's
        // not their turn yet. The armed check should already be off.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, MP_LOCAL_USER, contributedThisStreet = 0),
                    mpSeat(1, "opp1", stack = 900, contributedThisStreet = 100),
                    mpSeat(2, "opp2"),
                ),
                actingSeatIndex = 2,
                street = BettingRound.Flop,
                community = flop,
                currentBetThisStreet = 100,
                lastSequence = 1,
            ),
        )

        assertTable(s.table) { isHumanTurn(false) }
        assertNull(s.vm.state.armedPreAction, "the armed check disarms the moment a bet is out, even before our turn")
        assertTrue(s.sentFrames().none { it is ClientFrame.SubmitIntent })
    }
}
