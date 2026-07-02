package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.XpMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure tests for [PlayPokerState] derivations — chiefly [PlayPokerState.isRealMultiplayer],
 * which gates the terminal MP bust dialog vs the solo/practice rebuy dialog.
 * Tested directly (no VM) so every branch of the gate is pinned, including the
 * bots-only practice sub-case the fake session factory can't easily project.
 */
class PlayPokerStateTest {

    private fun table(practiceTierBotsOnly: Boolean): TableUiState.Active =
        TableUiState.fromGameState(
            gameState = stubGameState(),
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
            practiceTierBotsOnly = practiceTierBotsOnly,
        )

    @Test
    fun isRealMultiplayer_trueForMpTableThatIsNotBotsOnly() {
        val state = PlayPokerState(table = table(practiceTierBotsOnly = false), xpMode = XpMode.MULTIPLAYER)
        assertTrue(state.isRealMultiplayer)
    }

    @Test
    fun isRealMultiplayer_falseForBotsOnlyPracticeMpTable() {
        val state = PlayPokerState(table = table(practiceTierBotsOnly = true), xpMode = XpMode.MULTIPLAYER)
        assertFalse(state.isRealMultiplayer, "a bots-only practice MP table keeps the rebuy dialog")
    }

    @Test
    fun isRealMultiplayer_falseForBotsMode() {
        val state = PlayPokerState(table = table(practiceTierBotsOnly = false), xpMode = XpMode.BOTS)
        assertFalse(state.isRealMultiplayer, "solo bots is never real multiplayer")
    }

    @Test
    fun isRealMultiplayer_falseBeforeTableLoads() {
        val state = PlayPokerState(table = TableUiState.Loading, xpMode = XpMode.MULTIPLAYER)
        assertFalse(state.isRealMultiplayer, "no Active table yet → not yet real-MP")
    }

    // MP-31: backing out of a real-money MP seat must confirm even when the
    // table is stuck/degraded and never projected to Active. The old gate keyed
    // only on a live hand, so a degraded MP table (no Active projection) let the
    // player silently back out into a weird state.
    @Test
    fun requiresLeaveConfirmation_trueForDegradedRealMpSeat() {
        val state = PlayPokerState(table = TableUiState.Loading, xpMode = XpMode.MULTIPLAYER)
        assertTrue(
            state.requiresLeaveConfirmation,
            "a stuck/degraded MP seat must still confirm the leave (MP-31)",
        )
    }

    @Test
    fun requiresLeaveConfirmation_falseForDegradedSoloTable() {
        val state = PlayPokerState(table = TableUiState.Loading, xpMode = XpMode.BOTS)
        assertFalse(
            state.requiresLeaveConfirmation,
            "a solo table with nothing projected has nothing to lose — leave silently",
        )
    }

    @Test
    fun requiresLeaveConfirmation_trueForRealMpTable() {
        val state = PlayPokerState(table = table(practiceTierBotsOnly = false), xpMode = XpMode.MULTIPLAYER)
        assertTrue(state.requiresLeaveConfirmation, "real-money MP seat always confirms")
    }

    @Test
    fun requiresLeaveConfirmation_trueForSoloLiveHand() {
        val state = PlayPokerState(table = table(practiceTierBotsOnly = false), xpMode = XpMode.BOTS)
        assertTrue(
            state.requiresLeaveConfirmation,
            "a live hand always confirms, even solo — leaving costs the hand",
        )
    }
}
