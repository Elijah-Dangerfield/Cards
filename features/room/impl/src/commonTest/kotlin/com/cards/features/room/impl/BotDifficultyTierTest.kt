package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.ui.difficultyTierFor

import com.dangerfield.cards.libraries.bots.BotDifficulty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BotDifficultyTierTest {

    @Test
    fun resolvesAllThreeHomeScreenDifficulties() {
        for (difficulty in BotDifficulty.entries) {
            val tier = difficultyTierFor(difficulty.name)
            assertNotNull(tier, "Missing tier blurb for ${difficulty.name}")
            assertEquals(difficulty.name, tier.label)
        }
    }

    @Test
    fun returnsNullForUnknownLabel() {
        assertNull(difficultyTierFor("Practice"))
        assertNull(difficultyTierFor(""))
    }

    @Test
    fun descriptionsAreDistinctPerTier() {
        val descriptions = BotDifficulty.entries
            .mapNotNull { difficultyTierFor(it.name)?.description }
            .toSet()
        assertEquals(BotDifficulty.entries.size, descriptions.size)
    }
}
