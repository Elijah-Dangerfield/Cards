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
    private val shortestNounLen: Int = nouns.minOf { it.length }
    private val shortestAdjLen: Int = adjectives.minOf { it.length }

    init {
        check(adjectives.isNotEmpty()) { "adjectives.txt is empty or missing" }
        check(nouns.isNotEmpty()) { "nouns.txt is empty or missing" }
        // The budget logic in random() needs a shortest adj + shortest noun that
        // fit even with the longest (4-digit) suffix, or it could produce a name
        // over MAX_LENGTH. Fail loudly at boot if the word lists ever drift.
        check(shortestAdjLen + shortestNounLen + MAX_SUFFIX_DIGITS + DASHES <= MAX_LENGTH) {
            "Word lists too long to generate a name within $MAX_LENGTH chars"
        }
    }

    override fun random(): String {
        // Keep the whole name within MAX_LENGTH — the client's display cap — so a
        // freshly-generated default never trips the client's name rules (which
        // would soft-lock onboarding). Pick an adjective that leaves room for at
        // least the shortest noun, then a noun that fits the rest, so the name is
        // bounded by construction and never truncated mid-word.
        val suffix = random.nextInt(SUFFIX_MIN, SUFFIX_MAX + 1)
        val suffixLen = suffix.toString().length
        val maxAdjLen = MAX_LENGTH - suffixLen - shortestNounLen - DASHES
        val adj = adjectives.filter { it.length <= maxAdjLen }.random(random)
        val noun = nouns.filter { it.length <= MAX_LENGTH - adj.length - suffixLen - DASHES }
            .random(random)
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

        /** Widest suffix [SUFFIX_MAX] can render — used for the boot length check. */
        private const val MAX_SUFFIX_DIGITS = 4

        /** Two hyphens in `Adj-Noun-NNN`. */
        private const val DASHES = 2

        /**
         * Upper bound for a generated name. Matches the client's display cap
         * (`DisplayNameRules.MAX_LENGTH`) so a generated default is always a
         * valid client display name. Duplicated across the client/server
         * boundary by necessity (separate modules); keep them in sync.
         */
        private const val MAX_LENGTH = 16
    }
}
