package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import kotlin.test.Test
import kotlin.test.assertEquals

class BotPlayingStyleTest {

    @Test
    fun janeIsTightPassive() {
        assertEquals("Tight passive", playingStyleFor(BotPersonality.Jane).label)
    }

    @Test
    fun davidIsLooseAggressive() {
        assertEquals("Loose aggressive", playingStyleFor(BotPersonality.David).label)
    }

    @Test
    fun ginaIsTightAggressive() {
        assertEquals("Tight aggressive", playingStyleFor(BotPersonality.Gina).label)
    }

    @Test
    fun steveIsLoosePassive() {
        assertEquals("Loose passive", playingStyleFor(BotPersonality.Steve).label)
    }

    @Test
    fun mikeIsManiac() {
        assertEquals("Maniac", playingStyleFor(BotPersonality.Mike).label)
    }

    @Test
    fun centerOfTheRangeIsBalanced() {
        val balanced = BotPersonality(
            name = "Balanced",
            tightness = 0.50,
            aggression = 0.50,
            bluffRate = 0.10,
            avatarKey = "avatar_balanced",
            emoji = "🤖",
        )
        assertEquals("Balanced", playingStyleFor(balanced).label)
    }

    @Test
    fun descriptionsAreNonBlank() {
        for (p in BotPersonality.Roster) {
            val style = playingStyleFor(p)
            assertEquals(style.description, style.description.trim())
            check(style.description.isNotBlank()) { "Description was blank for ${p.name}" }
        }
    }
}
