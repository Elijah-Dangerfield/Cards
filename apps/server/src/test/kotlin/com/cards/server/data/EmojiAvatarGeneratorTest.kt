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
        val gen = EmojiAvatarGenerator()
        val seen = buildSet {
            repeat(500) { add(gen.random()) }
        }
        assertTrue(seen.size >= 20, "Expected ≥20 distinct emojis in 500 draws, got ${seen.size}")
    }
}
