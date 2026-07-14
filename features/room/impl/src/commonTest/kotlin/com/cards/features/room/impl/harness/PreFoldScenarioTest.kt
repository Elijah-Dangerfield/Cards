package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end behaviour for the pre-fold control (GAME-30): a real
 * [PlayPokerViewModel] arms a pre-fold while waiting, and it folds — always a
 * fold — the moment the human's turn arrives, whatever the action did in the
 * meantime. Solo covers arm-fires-immediately; MP covers the real "waiting →
 * opponent acts → auto-folds" turn arrival plus cancel and new-hand retirement,
 * using frame-level control the solo bot loop can't pause for.
 *
 * Replaces the old conditional "Check/Fold" + "Check" pre-action pair, which
 * exposed poker power-user semantics as two near-identical buttons.
 */
class PreFoldScenarioTest : PokerScenarioTest() {

    private val flop = cards("Ah Kd Qs")

    // ---------- Solo: arm fires immediately on the human's turn ----------

    @Test
    fun armedPreFold_foldsImmediately_whenItIsAlreadyTheHumansTurn() = runUnitTest {
        val s = soloScenario().seats(2).start()
        assertTable(s.table) { isHumanTurn(true) }

        s.armPreFold(true)

        assertTable(s.table) {
            street(BettingRound.Complete)
            handResultShowing()
            handResultWinner(seat = 1)
            seatFolded(seat = 0)
        }
        assertFalse(s.vm.state.preFoldArmed, "the pre-fold clears once it fires")
    }

    // ---------- MP: turn arrival ----------

    @Test
    fun armedPreFold_folds_whenTheTurnArrives_evenThoughCheckingIsFree() = runUnitTest {
        // The defining difference from the old conditional "Check/Fold": a
        // pre-fold folds even when the human could have checked for free. The
        // gesture said "I'm out of this hand," so we honor it literally.
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

        s.armPreFold(true)

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
        assertEquals(
            PlayerIntent.Fold(0),
            submitted?.intent,
            "a pre-fold folds on turn arrival, even when a free check was available",
        )
        assertFalse(s.vm.state.preFoldArmed)
    }

    @Test
    fun armedPreFold_folds_whenTheTurnArrivesFacingABet() = runUnitTest {
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

        s.armPreFold(true)

        // Opponent bets → the turn arrives with a bet to face; still a fold.
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
        assertEquals(PlayerIntent.Fold(0), submitted?.intent)
        assertFalse(s.vm.state.preFoldArmed)
    }

    // ---------- MP: cancel + new-hand retirement ----------

    @Test
    fun canceledPreFold_doesNotFire_whenTheTurnArrives() = runUnitTest {
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

        s.armPreFold(true)
        s.armPreFold(false) // player changed their mind before it fired

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

        assertTrue(
            s.sentFrames().none { it is ClientFrame.SubmitIntent },
            "a canceled pre-fold must never auto-submit",
        )
        assertFalse(s.vm.state.preFoldArmed)
        assertTable(s.table) { isHumanTurn(true) }
    }

    @Test
    fun armedPreFold_doesNotSurviveIntoTheNextHand() = runUnitTest {
        val s = mpScenario().start()
        // Hand 1: waiting on the opponent, pre-fold armed but never reached.
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
        s.armPreFold(true)

        // A fresh hand deals with the human first to act facing the blind — a
        // stale pre-fold would silently fold hand 2. It must not.
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

        assertFalse(s.vm.state.preFoldArmed, "an arm from the prior hand is retired on the new deal")
        assertTrue(
            s.sentFrames().none { it is ClientFrame.SubmitIntent },
            "no auto-fold fires on the fresh hand",
        )
        assertTable(s.table) { isHumanTurn(true); humanCannotCheck() }
    }
}
