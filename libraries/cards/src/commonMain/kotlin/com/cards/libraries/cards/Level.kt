package com.dangerfield.cards.libraries.cards

/**
 * Derived level for a player's lifetime XP.
 *
 * Level N requires `N² × 100` XP to advance to N+1 — quadratic so early
 * levels feel like quick wins and later levels feel earned:
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
 * XP itself never resets — this is purely a derived view of the same number.
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
 * Maps a lifetime XP total to its current [LevelProgress].
 *
 * Iterates from level 1 forward; bounded by [MAX_LEVEL] so a bogus XP value
 * can never spin forever.
 */
fun levelProgressFor(totalXp: Long): LevelProgress {
    val xp = totalXp.coerceAtLeast(0)
    var level = 1
    var cumulative = 0L
    while (level < MAX_LEVEL) {
        val needed = xpToLevelUpFrom(level)
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
        xpForNextLevel = xpToLevelUpFrom(MAX_LEVEL),
    )
}

/** XP needed to advance from `level` to `level + 1`. */
fun xpToLevelUpFrom(level: Int): Long {
    val n = level.coerceAtLeast(1).toLong()
    return n * n * 100L
}

/** Safety bound; nobody will hit this in normal play. */
const val MAX_LEVEL: Int = 100
