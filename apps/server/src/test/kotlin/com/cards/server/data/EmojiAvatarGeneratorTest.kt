package com.dangerfield.cards.server.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class EmojiAvatarGeneratorTest {

    @Test
    fun random_returnsNonEmptyString() {
        val gen = EmojiAvatarGenerator(random = Random(seed = 0))
        repeat(50) {
            val emoji = gen.random()
            assertTrue(emoji.isNotEmpty(), "Expected non-empty emoji")
        }
    }

    @Test
    fun largeSamplingCoversManyDistinctEmoji() {
        // A well-formed pool returns a varied result. If we accidentally
        // shipped a one-element pool, the assertion below would catch it.
        // Threshold sits at floor(starter.size * 0.75) so the test stays
        // robust to small pool tweaks while still failing loud if the
        // pool collapses.
        val gen = EmojiAvatarGenerator()
        val poolSize = com.dangerfield.cards.server.domain.AvatarStarterPack.values.size
        val expectedMin = (poolSize * 3 / 4).coerceAtLeast(4)
        val seen = buildSet {
            repeat(500) { add(gen.random()) }
        }
        assertTrue(
            seen.size >= expectedMin,
            "Expected ≥$expectedMin distinct emojis in 500 draws (pool=$poolSize), got ${seen.size}",
        )
    }
}
