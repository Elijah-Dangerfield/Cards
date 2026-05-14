package com.dangerfield.cards.libraries.cards

/**
 * The user's lifetime progression snapshot — XP plus engagement counters.
 *
 * XP is decoupled from win/lose per docs/decisions.md (2026-05-14):
 * winning vs. losing the same hand at the same investment level earns the
 * same XP. The `handsWon` / `handsLostAtShowdown` counters live here only
 * for display, not as XP inputs.
 */
data class Progression(
    val totalXp: Long,
    val handsPlayed: Long,
    val handsWon: Long,
    val handsFolded: Long,
    val handsLostAtShowdown: Long,
    val botHandsPlayed: Long,
    val updatedAtEpochMs: Long,
) {
    companion object {
        val Empty: Progression = Progression(
            totalXp = 0,
            handsPlayed = 0,
            handsWon = 0,
            handsFolded = 0,
            handsLostAtShowdown = 0,
            botHandsPlayed = 0,
            updatedAtEpochMs = 0,
        )
    }
}

/** Reason a chunk of XP was awarded. Persisted as a string for forward-compat. */
enum class XpSource {
    /** Awarded for finishing a hand (even a fold). */
    BASE,

    /** Scales with chips voluntarily put in the pot. */
    INVESTMENT,

    /** Awarded for reaching showdown. */
    SHOWDOWN,

    /** Awarded for the strength of the hand shown at showdown. */
    HAND_STRENGTH,
}

/** Whether a hand was played against bots or in a multiplayer room. */
enum class XpMode {
    BOTS,
    MULTIPLAYER,
}

/** One row of the XP ledger — what was awarded, when, and why. */
data class XpEvent(
    val id: Long,
    val deltaXp: Int,
    val source: XpSource,
    val mode: XpMode,
    val handId: String?,
    val createdAtEpochMs: Long,
)
