package com.dangerfield.cards.libraries.achievements

import kotlinx.serialization.Serializable

/**
 * The materialized projection of a player's [HandFacts] history into the counter
 * values achievement predicates read — a keyed `name -> value` map so a new
 * achievement can point at an existing (or newly folded) counter with no schema
 * migration. The single source of truth for "how a hand moves the numbers" is
 * [fold]; client (optimistic) and server (authority) run the identical function.
 *
 * Counters are one of a few shapes, all derivable by an *ordered* fold and never
 * by summing:
 *  - **accumulators** (`hands_played`, `good_fold`) — `+1` (or `+n`) per qualifying hand;
 *  - **high-water marks** (`max_pot_seen`, `best_no_bust_streak`) — running max;
 *  - **streaks** (`no_bust_streak`) — reset-or-increment in arrival order;
 *  - **armed flags** (`short_stack_armed`) — a 0/1 latch driving a later fire;
 *  - **per-key families** (`wins_vs_bot_<id>`) — one accumulator per dynamic key.
 *
 * Two derived read-time signals are intentionally NOT stored: the player's level
 * (it lives in the XP/progression aggregate) and "bots beaten ≥ N" (a count over
 * the `wins_vs_bot_*` family) — compute those where the snapshot is assembled.
 */
@Serializable
data class AchievementCounters(val values: Map<String, Long> = emptyMap()) {

    operator fun get(key: String): Long = values[key] ?: 0L

    /** Fold one finished hand into the counters, returning the next snapshot. Pure. */
    fun fold(f: HandFacts): AchievementCounters {
        val m = values.toMutableMap()
        fun inc(key: String, by: Long = 1) { m[key] = (m[key] ?: 0L) + by }
        fun setMax(key: String, candidate: Long) { m[key] = maxOf(m[key] ?: 0L, candidate) }
        fun set(key: String, v: Long) { m[key] = v }

        // --- core volume ---
        inc(HANDS_PLAYED)
        if (f.won) inc(HANDS_WON)
        if (f.folded) inc(HANDS_FOLDED)
        if (f.lostAtShowdown) inc(HANDS_LOST_AT_SHOWDOWN)
        if (f.vsBot) inc(BOT_HANDS_PLAYED)

        // --- no-bust streak (reset on bust, else +1; best = running max) ---
        val nextStreak = if (f.busted) 0L else get(NO_BUST_STREAK) + 1
        set(NO_BUST_STREAK, nextStreak)
        setMax(BEST_NO_BUST_STREAK, nextStreak)

        // --- per-bot wins + challenging wins ---
        if (f.won && f.vsBot && f.beatenBotId != null) inc(winsVsBot(f.beatenBotId))
        if (f.won && f.botDifficulty == DIFFICULTY_CHALLENGING) inc(CHALLENGING_WINS)

        // --- decisive-action counters ---
        if (f.wonByFold) inc(WIN_BY_FOLD)
        if (f.folded && f.foldedWouldHaveLost) inc(GOOD_FOLD)
        if (f.wasAllIn) inc(ALL_IN_HANDS)
        if (f.bustsDealt > 0) inc(BUSTS_DEALT, f.bustsDealt.toLong())

        // --- pot high-water marks ---
        setMax(MAX_POT_SEEN, f.potTotal)
        if (f.bigBlind > 0) setMax(MAX_POT_BB_RATIO, f.potTotal / f.bigBlind)

        // --- stack swings ---
        if (f.startStack > 0 && f.endStack >= f.startStack * 2) inc(DOUBLED_UP)
        if (f.startStack > 0 && f.endStack >= f.startStack * 3) inc(TRIPLED_UP)

        // --- comebacks (blind-relative, end-of-hand definitions) ---
        // Started the hand short (≤ 5 BB) and at least doubled it.
        if (f.bigBlind > 0 && f.startStack in 1..(5 * f.bigBlind) && f.endStack >= f.startStack * 2) {
            inc(COMEBACK_5BB)
        }
        // "Don't call it a comeback": arm when you end a hand at ≤ 10 BB, fire the
        // first time you then end at ≥ 100 BB. A 0/1 latch so it can't double-fire.
        if (f.bigBlind > 0) {
            val armed = get(SHORT_STACK_ARMED) == 1L
            when {
                armed && f.endStack >= 100 * f.bigBlind -> {
                    inc(DONT_CALL_IT_COMEBACK)
                    set(SHORT_STACK_ARMED, 0)
                }
                f.endStack in 1..(10 * f.bigBlind) -> set(SHORT_STACK_ARMED, 1)
            }
        }

        // --- hand-strength shows: "show at least category C" increments for every
        // category up to the one actually shown (a flush counts toward show-pair too) ---
        f.handStrengthShown
            ?.let { name -> ShownHand.entries.firstOrNull { it.name == name } }
            ?.let { shown -> ShownHand.entries.filter { it.ordinal <= shown.ordinal }.forEach { inc(showKey(it)) } }

        return AchievementCounters(m)
    }

    companion object {
        val EMPTY = AchievementCounters()

        // Core volume.
        const val HANDS_PLAYED = "hands_played"
        const val HANDS_WON = "hands_won"
        const val HANDS_FOLDED = "hands_folded"
        const val HANDS_LOST_AT_SHOWDOWN = "hands_lost_at_showdown"
        const val BOT_HANDS_PLAYED = "bot_hands_played"

        // Streaks.
        const val NO_BUST_STREAK = "no_bust_streak"
        const val BEST_NO_BUST_STREAK = "best_no_bust_streak"

        // Decisive actions.
        const val CHALLENGING_WINS = "challenging_wins"
        const val WIN_BY_FOLD = "win_by_fold"
        const val GOOD_FOLD = "good_fold"
        const val ALL_IN_HANDS = "all_in_hands"
        const val BUSTS_DEALT = "busts_dealt"

        // High-water marks.
        const val MAX_POT_SEEN = "max_pot_seen"
        const val MAX_POT_BB_RATIO = "max_pot_bb_ratio"

        // Stack swings.
        const val DOUBLED_UP = "doubled_up"
        const val TRIPLED_UP = "tripled_up"

        // Comebacks.
        const val COMEBACK_5BB = "comeback_5bb"
        const val DONT_CALL_IT_COMEBACK = "dont_call_it_comeback"
        const val SHORT_STACK_ARMED = "short_stack_armed"

        /** Bot difficulty label that counts toward [CHALLENGING_WINS]. */
        const val DIFFICULTY_CHALLENGING = "Challenging"

        /** Per-bot win counter key, e.g. `wins_vs_bot_Jane`. */
        fun winsVsBot(botId: String): String = "wins_vs_bot_$botId"

        /** Per-category "showed at least this hand" counter key, e.g. `show_Flush`. */
        fun showKey(hand: ShownHand): String = "show_${hand.name}"
    }
}
