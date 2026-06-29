package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.BotTiming
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.cards.GameSpeed
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins that the "Game speed" setting drives bot think time — Fast makes bots act
 * quicker than Normal.
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
        assertTrue(fast < normal, "Fast ($fast) should think quicker than Normal ($normal)")
    }

    private fun think(speed: GameSpeed): Long = BotTiming.thinkDelayMs(
        personality = BotPersonality.David,
        thought = marginalThought,
        userPaceMs = null,
        speed = speed,
    )
}
