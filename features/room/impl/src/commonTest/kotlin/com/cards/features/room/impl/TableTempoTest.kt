package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.ui.TableTempo
import com.dangerfield.cards.libraries.cards.GameSpeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the play-screen pacing math behind the GAME-6 "Table speed" setting:
 * Normal plays the calibrated delays, Fast halves them, and Instant collapses
 * every cosmetic delay to zero (so dealt and revealed cards snap to settled).
 */
class TableTempoTest {

    @Test
    fun `normal speed leaves delays and durations unchanged`() {
        val tempo = TableTempo(GameSpeed.Normal)
        assertFalse(tempo.isInstant)
        assertEquals(340L, tempo.delay(340))
        assertEquals(340, tempo.duration(340))
    }

    @Test
    fun `fast speed halves delays and durations`() {
        val tempo = TableTempo(GameSpeed.Fast)
        assertFalse(tempo.isInstant)
        assertEquals(170L, tempo.delay(340))
        assertEquals(170, tempo.duration(340))
    }

    @Test
    fun `instant speed collapses every delay to zero`() {
        val tempo = TableTempo(GameSpeed.Instant)
        assertTrue(tempo.isInstant)
        assertEquals(0L, tempo.delay(340))
        assertEquals(0L, tempo.delay(0))
    }

    @Test
    fun `instant duration is zero so tween snaps`() {
        val tempo = TableTempo(GameSpeed.Instant)
        assertEquals(0, tempo.duration(380))
    }

    @Test
    fun `default tempo is normal`() {
        assertEquals(GameSpeed.Normal, TableTempo().speed)
    }
}
