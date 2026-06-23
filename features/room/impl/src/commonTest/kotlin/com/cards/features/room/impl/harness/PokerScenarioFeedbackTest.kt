package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.LocalBotsSession

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Behaviour suite for the one-off haptic/sound feedback the VM emits as
 * [PlayPokerEvent.PlayHaptic] / [PlayPokerEvent.PlaySound]. Drives a real VM
 * over a real [LocalBotsSession] and asserts on the [EventRecorder] capture —
 * "winning a hand buzzed HandWon", "busting buzzed Bust".
 *
 * Heads-up, Default blinds (SB 5 / BB 10); human is seat 0, bot is seat 1.
 */
class PokerScenarioFeedbackTest : PokerScenarioTest() {

    @Test
    fun winningAHand_firesHandWonHaptic_andShowdownSound() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { folds() } // bot folds to the raise → human wins
            .start()

        s.iRaiseTo(30)

        assertTable(s.table) { handResultWinner(seat = 0) }
        assertContains(s.events.haptics(), HapticKind.HandWon)
        assertContains(s.events.sounds(), SoundKind.Showdown)
        assertFalse(
            HapticKind.HandLost in s.events.haptics(),
            "winning must not also fire HandLost",
        )
    }

    @Test
    fun losingAllInThatBustsTheHuman_firesBustHaptic() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .deal("Kh Kc", "Ah Ad") // human pair K vs bot pair A → bot wins
            .board("Ac 7c 2s 9h 3d") // bot flops trip aces; human never ahead
            .scriptOpponent(1) { calls() } // call the all-in
            .start()

        s.iAllIn()

        assertTable(s.table) {
            handResultWinner(seat = 1)
            seatBusted(seat = 0)
        }
        assertContains(s.events.haptics(), HapticKind.HandLost)
        assertContains(s.events.haptics(), HapticKind.Bust)
    }

    @Test
    fun submittingAChipAction_firesActionTakenHaptic_andChipClickSound() = runUnitTest {
        val s = soloScenario()
            .seats(2)
            .scriptOpponent(1) { checks() } // bot checks the BB option after the call
            .start()

        s.iCall() // preflop limp — chips move

        assertContains(s.events.haptics(), HapticKind.ActionTaken)
        assertContains(s.events.sounds(), SoundKind.ChipClick)
    }
}
