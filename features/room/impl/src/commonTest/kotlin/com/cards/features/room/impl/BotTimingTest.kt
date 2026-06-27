package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.BotTiming
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.cards.GameSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the single "Game speed" setting drives bot think time, not just the
 * card animations — a faster tier makes bots act quicker, and Instant snaps them
 * to the hard floor so a hand resolves with no waiting.
 */
class BotTimingTest {

    // A marginal spot (handStrength ≈ potOdds) gives the longest base think, so
    // the speed scaling has the most headroom to show up.
    private val marginalThought = BotThought(
        handStrength = 0.5,
        potOdds = 0.5,
        drawProfile = null,
        opponentNote = null,
        rationale = "",
    )

    @Test
    fun `faster game speed yields a shorter bot think time`() {
        val normal = think(GameSpeed.Normal)
        val fast = think(GameSpeed.Fast)
        val instant = think(GameSpeed.Instant)
        assertTrue(fast < normal, "Fast ($fast) should think quicker than Normal ($normal)")
        assertTrue(instant <= fast, "Instant ($instant) should be no slower than Fast ($fast)")
    }

    @Test
    fun `instant game speed snaps bots to the hard floor`() {
        // Instant's 0.0 scale collapses to the 250ms glitch floor — bots act
        // immediately without the snap reading as a render bug.
        assertEquals(250L, think(GameSpeed.Instant))
    }

    private fun think(speed: GameSpeed): Long = BotTiming.thinkDelayMs(
        personality = BotPersonality.David,
        thought = marginalThought,
        userPaceMs = null,
        speed = speed,
    )
}
