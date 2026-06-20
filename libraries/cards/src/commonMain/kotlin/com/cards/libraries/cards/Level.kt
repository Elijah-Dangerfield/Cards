package com.dangerfield.cards.libraries.cards

import kotlinx.serialization.Serializable

/**
 * The XP-per-level curve. The default is quadratic — level N needs `N² × 100`
 * XP to advance to N+1, so early levels feel like quick wins and later levels
 * feel earned:
 *
 * | Level | Cumulative XP to reach | XP to next level |
 * | ----: | ---------------------: | ---------------: |
 * |   1   |                  0     |              100 |
 * |   2   |                100     |              400 |
 * |   3   |                500     |              900 |
 * |   5   |              3,000     |            2,500 |
 * |  10   |             28,500     |           10,000 |
 * |  20   |            247,000     |           40,000 |
 *
 * At ~15 XP/hand against bots, that's roughly:
 * - Level 1 → 2 in ~7 hands
 * - Level 5 → 6 in ~165 hands
 * - Level 10 → 11 in ~660 hands
 *
 * The curve is **server-tunable**: it rides app-config (`progression.levelCurve`)
 * so the economy can be retuned without shipping a build. [xpPerLevel] is an
 * optional front-loaded ladder — entry `i` is the XP to advance from level `i+1`
 * to `i+2`; levels past the ladder fall back to `baseXp × level^exponent`. The
 * bundled [DefaultLevelCurve] (empty ladder, `100 × N²`) reproduces the table
 * above. Derive a level through [ProgressionConfig.levelCurve] on the
 * authoritative grant / persisted-counter paths so display and grant never read
 * a different curve. See `docs/decisions.md` 2026-06-17.
 */
@Serializable
data class LevelCurve(
    val xpPerLevel: List<Long> = emptyList(),
    val baseXp: Long = DEFAULT_LEVEL_CURVE_BASE_XP,
    val exponent: Int = DEFAULT_LEVEL_CURVE_EXPONENT,
) {
    /** XP needed to advance from [level] to `level + 1` under this curve. */
    fun xpToLevelUpFrom(level: Int): Long {
        val n = level.coerceAtLeast(1)
        xpPerLevel.getOrNull(n - 1)?.let { return it.coerceAtLeast(0L) }
        var power = 1L
        repeat(exponent.coerceAtLeast(0)) { power *= n }
        return baseXp * power
    }
}

private const val DEFAULT_LEVEL_CURVE_BASE_XP = 100L
private const val DEFAULT_LEVEL_CURVE_EXPONENT = 2

/** The bundled curve: `100 × N²`. A server value replaces it via app-config. */
val DefaultLevelCurve: LevelCurve = LevelCurve()

/**
 * XP itself never resets — [LevelProgress] is purely a derived view of the same
 * number against a [LevelCurve].
 */
data class LevelProgress(
    val level: Int,
    val totalXp: Long,
    val xpAtLevelStart: Long,
    val xpForNextLevel: Long,
) {
    /** XP earned within the current level (0..xpForNextLevel). */
    val xpIntoLevel: Long get() = totalXp - xpAtLevelStart

    /** XP still needed to reach the next level. */
    val xpToNextLevel: Long get() = (xpAtLevelStart + xpForNextLevel) - totalXp

    /** 0f..1f progress through the current level. */
    val fraction: Float get() = if (xpForNextLevel <= 0) 0f
        else (xpIntoLevel.toFloat() / xpForNextLevel.toFloat()).coerceIn(0f, 1f)
}

/**
 * Maps a lifetime XP total to its current [LevelProgress] under [curve]
 * (defaulting to the bundled [DefaultLevelCurve]).
 *
 * Iterates from level 1 forward; bounded by [MAX_LEVEL] so a bogus XP value
 * can never spin forever.
 */
fun levelProgressFor(totalXp: Long, curve: LevelCurve = DefaultLevelCurve): LevelProgress {
    val xp = totalXp.coerceAtLeast(0)
    var level = 1
    var cumulative = 0L
    while (level < MAX_LEVEL) {
        val needed = curve.xpToLevelUpFrom(level)
        if (cumulative + needed > xp) {
            return LevelProgress(
                level = level,
                totalXp = xp,
                xpAtLevelStart = cumulative,
                xpForNextLevel = needed,
            )
        }
        cumulative += needed
        level++
    }
    return LevelProgress(
        level = MAX_LEVEL,
        totalXp = xp,
        xpAtLevelStart = cumulative,
        xpForNextLevel = curve.xpToLevelUpFrom(MAX_LEVEL),
    )
}

/** XP needed to advance from `level` to `level + 1` under [curve]. */
fun xpToLevelUpFrom(level: Int, curve: LevelCurve = DefaultLevelCurve): Long =
    curve.xpToLevelUpFrom(level)

/** Cumulative XP required to *reach* (sit at the start of) [level] under [curve]. */
fun xpAtStartOfLevel(level: Int, curve: LevelCurve = DefaultLevelCurve): Long {
    val target = level.coerceAtLeast(1)
    var sum = 0L
    for (n in 1 until target) sum += curve.xpToLevelUpFrom(n)
    return sum
}

/** Safety bound; nobody will hit this in normal play. */
const val MAX_LEVEL: Int = 100
