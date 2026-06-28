package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.ui.PreviewSamples
import com.dangerfield.cards.libraries.cards.GameSpeed
import com.dangerfield.cards.libraries.cards.XpMode

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GAME-8: the global "Game speed" setting paces solo / practice tables, but it
 * must never strip the deal / reveal animations off a game where real chips are
 * at stake — a real human MP game or a public subsidized-bot table. The
 * reporter set Instant for solo practice and lost the card flips in a real
 * bots-for-chips public room.
 */
class EffectiveTableSpeedTest {

    @Test
    fun `solo bot table honours the chosen game speed`() {
        val state = PlayPokerState(
            table = PreviewSamples.activeTable(),
            xpMode = XpMode.BOTS,
            gameSpeed = GameSpeed.Instant,
        )
        assertEquals(GameSpeed.Instant, state.effectiveTableSpeed)
    }

    @Test
    fun `real human MP table keeps normal animations even when set to instant`() {
        val state = PlayPokerState(
            table = PreviewSamples.activeTable(practiceTierBotsOnly = false),
            xpMode = XpMode.MULTIPLAYER,
            gameSpeed = GameSpeed.Instant,
        )
        assertEquals(GameSpeed.Normal, state.effectiveTableSpeed)
    }

    @Test
    fun `subsidized public-bot table keeps normal animations even when set to instant`() {
        val state = PlayPokerState(
            table = PreviewSamples.activeTable(
                practiceTierBotsOnly = true,
                subsidizedBotTable = true,
            ),
            xpMode = XpMode.MULTIPLAYER,
            gameSpeed = GameSpeed.Instant,
        )
        assertEquals(GameSpeed.Normal, state.effectiveTableSpeed)
    }

    @Test
    fun `private practice-bot table honours the chosen game speed`() {
        val state = PlayPokerState(
            table = PreviewSamples.activeTable(
                practiceTierBotsOnly = true,
                subsidizedBotTable = false,
            ),
            xpMode = XpMode.MULTIPLAYER,
            gameSpeed = GameSpeed.Fast,
        )
        assertEquals(GameSpeed.Fast, state.effectiveTableSpeed)
    }
}
