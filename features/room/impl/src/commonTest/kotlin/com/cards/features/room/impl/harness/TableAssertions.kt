package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.ui.label

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assertion vocabulary over [TableUiState.Active] — what the player actually
 * sees. Works identically whether the table was produced by a solo-bots
 * scenario or a multiplayer one, so both surfaces assert with one DSL.
 *
 * ```
 * assertTable(scenario.table) {
 *     street(BettingRound.Flop)
 *     humanCanCall(150)
 *     seatPill(1, "Raised to 200")
 *     humanHandLabel("Pair")
 * }
 * ```
 */
fun assertTable(table: TableUiState.Active, block: TableAssertionScope.() -> Unit) {
    TableAssertionScope(table).block()
}

class TableAssertionScope(val table: TableUiState.Active) {

    fun street(expected: BettingRound) =
        assertEquals(expected, table.street, "street")

    fun handNumber(expected: Int) =
        assertEquals(expected, table.handNumber, "hand number")

    fun isHumanTurn(expected: Boolean = true) =
        assertEquals(expected, table.isHumanTurn, "isHumanTurn")

    fun humanCanCall(amount: Long) {
        val legal = assertNotNull(table.humanLegalActions, "expected legal actions on the human's turn")
        assertTrue(legal.canCall, "expected canCall=true")
        assertEquals(amount, legal.callAmount, "call amount")
    }

    fun humanCanCheck() {
        val legal = assertNotNull(table.humanLegalActions, "expected legal actions on the human's turn")
        assertTrue(legal.canCheck, "expected canCheck=true")
    }

    fun humanCannotCheck() {
        val legal = assertNotNull(table.humanLegalActions, "expected legal actions on the human's turn")
        assertFalse(legal.canCheck, "expected canCheck=false (facing a bet)")
    }

    fun humanHandLabel(expected: String?) =
        assertEquals(expected, table.humanHandLabel, "human hand label")

    /** Assert the action pill rendered under [seat] reads [expected] (via [shortLabel]). */
    fun seatPill(seat: Int, expected: String) {
        val view = table.seats.firstOrNull { it.index == seat }
            ?: throw AssertionError("no seat $seat in table")
        val pill = view.lastAction?.shortLabel()
        assertEquals(expected, pill, "seat $seat action pill")
    }

    /** Assert [seat] shows no action pill (its `lastAction` is cleared). */
    fun noSeatPill(seat: Int) {
        val view = table.seats.firstOrNull { it.index == seat }
            ?: throw AssertionError("no seat $seat in table")
        assertEquals(null, view.lastAction, "seat $seat action pill should be cleared")
    }

    fun seatBusted(seat: Int, expected: Boolean = true) {
        val view = table.seats.firstOrNull { it.index == seat }
            ?: throw AssertionError("no seat $seat in table")
        assertEquals(expected, view.isBusted, "seat $seat busted")
    }

    /** Assert [seat] shows [count] hole cards (e.g. 2 once revealed at showdown). */
    fun seatHoleCardsVisible(seat: Int, count: Int = 2) {
        val view = table.seats.firstOrNull { it.index == seat }
            ?: throw AssertionError("no seat $seat in table")
        assertEquals(count, view.holeCards.size, "seat $seat visible hole cards")
    }

    fun seatFolded(seat: Int) {
        val view = table.seats.firstOrNull { it.index == seat }
            ?: throw AssertionError("no seat $seat in table")
        assertEquals(HandParticipation.Folded, view.participation, "seat $seat participation")
    }

    /** The showdown / hand-result dialog is open. */
    fun handResultShowing() =
        assertNotNull(table.handResult, "expected the hand-result dialog to be showing")

    /** No hand-result dialog (hand in progress). */
    fun noHandResult() =
        assertNull(table.handResult, "expected no hand-result dialog mid-hand")

    fun handResultWinner(seat: Int) {
        val result = assertNotNull(table.handResult, "expected the hand-result dialog to be showing")
        assertTrue(
            result.winners.any { it.seatIndex == seat },
            "expected seat $seat among winners, got ${result.winners.map { it.seatIndex }}",
        )
    }
}

/** Convenience for the common "who won" assertion outside an [assertTable] block. */
fun assertHandResultWinner(table: TableUiState.Active, seat: Int) =
    assertTable(table) { handResultWinner(seat) }
