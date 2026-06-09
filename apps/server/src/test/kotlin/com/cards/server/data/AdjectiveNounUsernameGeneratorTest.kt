package com.dangerfield.cards.server.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdjectiveNounUsernameGeneratorTest {

    @Test
    fun random_producesRedditStyleName() {
        val gen = AdjectiveNounUsernameGenerator(random = Random(seed = 0))
        val name = gen.random()
        // Format: Adjective-Noun-NNN
        val parts = name.split('-')
        assertEquals(3, parts.size, "Expected three hyphen-separated parts in $name")
        val (adj, noun, suffix) = parts
        assertTrue(adj.isNotEmpty() && adj[0].isUpperCase(), "Adjective should start uppercase: $name")
        assertTrue(noun.isNotEmpty() && noun[0].isUpperCase(), "Noun should start uppercase: $name")
        assertTrue(
            suffix.length in 3..4 && suffix.all { it.isDigit() },
            "Suffix must be 3-4 digits: '$suffix' in $name",
        )
    }

    @Test
    fun generatedNamesNeverExceedClientMaxLength() {
        // Generated defaults must fit the client's 16-char display cap, or
        // onboarding's Continue button soft-locks on a name the user didn't type.
        val gen = AdjectiveNounUsernameGenerator()
        repeat(20_000) {
            val name = gen.random()
            assertTrue(name.length <= 16, "Generated name exceeds 16 chars: '$name' (${name.length})")
        }
    }

    @Test
    fun seededRandomIsDeterministic() {
        val a = AdjectiveNounUsernameGenerator(random = Random(seed = 12345)).random()
        val b = AdjectiveNounUsernameGenerator(random = Random(seed = 12345)).random()
        val c = AdjectiveNounUsernameGenerator(random = Random(seed = 99999)).random()
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun largeSamplingHasZeroCollisions() {
        // Reddit-style space is ~450M; 5000 draws should never collide.
        val gen = AdjectiveNounUsernameGenerator()
        val names = List(5000) { gen.random() }
        val uniques = names.toSet().size
        assertEquals(5000, uniques, "Expected zero collisions in 5000 draws, got ${5000 - uniques}")
    }

    @Test
    fun suffixCoversFullRange() {
        // Sanity: 1000 draws should hit numbers all over the 100-9999
        // range. If we accidentally shipped a 0-9 range generator, this
        // catches it.
        val gen = AdjectiveNounUsernameGenerator()
        val suffixes = (1..1000).map { gen.random().split('-').last().toInt() }
        assertTrue(suffixes.min() < 500, "Expected at least one suffix below 500; got min=${suffixes.min()}")
        assertTrue(suffixes.max() > 5000, "Expected at least one suffix above 5000; got max=${suffixes.max()}")
    }
}
