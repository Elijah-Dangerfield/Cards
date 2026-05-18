package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.UsernameGenerator
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.random.Random

/**
 * `Adjective-Noun-NNN` generator backed by line-separated word lists in
 * `src/main/resources/usernames/`. Files are loaded once at construction
 * and held in memory — at our scale (~200 adjectives, ~250 nouns) that's
 * a few KB, well worth the zero per-call IO.
 *
 * Base space: `adjectives × nouns × 9000 suffix values ≈ 450M`. Collisions
 * are effectively impossible at any V1 scale; the unique constraint
 * retry loop on the repository is defensive.
 *
 * Random source: [Random.Default] (java.util.SplittableRandom under the
 * hood on JVM). Cryptographic randomness is unnecessary — adversaries
 * can't usefully predict the next name, and the unique constraint blocks
 * duplicates regardless.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class AdjectiveNounUsernameGenerator(
    private val random: Random = Random.Default,
) : UsernameGenerator {

    private val adjectives: List<String> = loadResource("usernames/adjectives.txt")
    private val nouns: List<String> = loadResource("usernames/nouns.txt")

    init {
        check(adjectives.isNotEmpty()) { "adjectives.txt is empty or missing" }
        check(nouns.isNotEmpty()) { "nouns.txt is empty or missing" }
    }

    override fun random(): String {
        val adj = adjectives.random(random)
        val noun = nouns.random(random)
        val suffix = random.nextInt(SUFFIX_MIN, SUFFIX_MAX + 1)
        return "$adj-$noun-$suffix"
    }

    private fun loadResource(path: String): List<String> {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("Resource not found: $path")
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
    }

    companion object {
        // 100..9999. Three to four digits — long enough to give the
        // combinatorial space breathing room, short enough to fit a chat
        // bubble.
        private const val SUFFIX_MIN = 100
        private const val SUFFIX_MAX = 9_999
    }
}
