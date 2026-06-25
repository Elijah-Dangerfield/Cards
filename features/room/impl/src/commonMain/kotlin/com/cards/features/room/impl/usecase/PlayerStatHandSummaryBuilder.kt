package com.dangerfield.cards.features.room.impl.usecase

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary

/**
 * Translates a finished hand ([HandResultSummary] + [AchievementHandContext])
 * into the [PlayerStatHandSummary] the server-authoritative stats outbox
 * accumulates.
 *
 * Stateful for one thing only: the no-bust streak. Streaks are order-dependent
 * (each hand's value is "prior + 1" or 0 on bust), so the server can't recompute
 * them from an unordered ledger — the client carries the running snapshot and
 * the server folds latest-current + running-max-best. [seedStreak] primes the
 * counter from the last synced snapshot so a session that starts mid-streak
 * keeps counting instead of restarting at 1.
 */
internal class PlayerStatHandSummaryBuilder {

    private var noBustStreak: Long = 0
    private var seeded: Boolean = false

    /** Prime the running streak from the cached snapshot; no-op after the first call. */
    fun seedStreak(current: Long) {
        if (!seeded) {
            noBustStreak = current
            seeded = true
        }
    }

    fun build(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): PlayerStatHandSummary {
        seeded = true
        val busted = context.humanEndingStack <= 0L
        noBustStreak = if (busted) 0 else noBustStreak + 1

        val vsBot = context.opponentBotNames.isNotEmpty()
        // The DTO carries a single beaten bot; achievements credit every bot at
        // the table, but the per-bot wins map keys on one id per hand. Pick the
        // first distinct opponent — correct for heads-up vs a bot (the common
        // case) and a defensible single attribution on a multi-bot table.
        val beatenBotId = if (summary.wonPot && vsBot) {
            context.opponentBotNames.distinct().firstOrNull()
        } else {
            null
        }

        return PlayerStatHandSummary(
            handId = summary.handId,
            mode = summary.mode,
            won = summary.wonPot,
            folded = summary.wasFold,
            lostAtShowdown = summary.reachedShowdown && !summary.wonPot,
            vsBot = vsBot,
            beatenBotId = beatenBotId,
            noBustStreak = noBustStreak,
        )
    }
}
