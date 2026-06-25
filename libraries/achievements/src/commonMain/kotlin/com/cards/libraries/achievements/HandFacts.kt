package com.dangerfield.cards.libraries.achievements

import kotlinx.serialization.Serializable

/**
 * The complete, raw record of one finished hand from a single player's point of
 * view — the durable domain event the achievement counters are folded from.
 *
 * This is the source of truth (stored append-only, keyed by [idempotencyKey]),
 * NOT pre-computed counters. Keeping the raw facts is what makes the projection
 * re-derivable: a new achievement added later can be back-filled by re-folding a
 * player's history, and the same [AchievementCounters.fold] runs identically on
 * the client (optimistic display) and the server (authority). Streaks and
 * high-water marks are derived by the *ordered* fold, never by summing — so the
 * server never needs the client to send a pre-computed streak (which a reinstall
 * would reset to zero and clobber).
 *
 * Producers: the client builds this for offline bot hands and flushes it; the
 * server builds it from `HandOutcome` for multiplayer hands. Both feed one fold.
 */
@Serializable
data class HandFacts(
    /** Stable per-hand dedup key — re-applying the same key is a no-op. */
    val idempotencyKey: String,
    /** [MODE_BOTS] or [MODE_MULTIPLAYER]. */
    val mode: String,
    val won: Boolean,
    val folded: Boolean,
    val lostAtShowdown: Boolean,
    val vsBot: Boolean,
    /** Bot beaten this hand, when [won] && [vsBot]; null otherwise. */
    val beatenBotId: String? = null,
    /** Bot difficulty this hand was played at (e.g. "Challenging"); null for multiplayer. */
    val botDifficulty: String? = null,
    /** True when the player's stack hit zero this hand (resets the no-bust streak). */
    val busted: Boolean = false,
    /** The player's stack at the start of the hand (chips). */
    val startStack: Long = 0,
    /** The player's stack after the hand settled (chips). */
    val endStack: Long = 0,
    /** The big blind in play, for blind-relative thresholds (pot/BB, short-stack). */
    val bigBlind: Long = 0,
    /** Total chips in the pot this hand. */
    val potTotal: Long = 0,
    /** The player committed their whole stack this hand. */
    val wasAllIn: Boolean = false,
    /** The player won without a showdown (everyone folded). */
    val wonByFold: Boolean = false,
    /** Opponents this player busted to zero this hand. */
    val bustsDealt: Int = 0,
    /** The player folded a hand that would have lost at showdown (a "good fold"). */
    val foldedWouldHaveLost: Boolean = false,
    /** Best 5-card category the player *showed* at showdown (see [ShownHand]); null if they didn't show. */
    val handStrengthShown: String? = null,
) {
    companion object {
        const val MODE_BOTS = "BOTS"
        const val MODE_MULTIPLAYER = "MULTIPLAYER"
    }
}

/**
 * The nine poker hand categories an achievement can reward *showing* at
 * showdown. Stored on [HandFacts.handStrengthShown] as [name] so the module
 * stays independent of the gameplay engine's evaluator; producers map their
 * own hand rank onto these. Stable — these are the rules of poker.
 */
enum class ShownHand {
    Pair,
    TwoPair,
    ThreeOfAKind,
    Straight,
    Flush,
    FullHouse,
    FourOfAKind,
    StraightFlush,
    RoyalFlush,
}
