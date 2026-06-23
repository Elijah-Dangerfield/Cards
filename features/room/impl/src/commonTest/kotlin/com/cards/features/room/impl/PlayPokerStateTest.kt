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
}
