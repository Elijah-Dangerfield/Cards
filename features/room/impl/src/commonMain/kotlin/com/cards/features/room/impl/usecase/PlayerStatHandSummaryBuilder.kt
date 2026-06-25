package com.dangerfield.cards.features.room.impl.usecase

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary

/**
 * Translates a finished hand ([HandResultSummary] + [AchievementHandContext])
 * into the [PlayerStatHandSummary] the server-authoritative stats outbox
 * accumulates — the complete raw facts the server folds into every achievement
 * counter, so nothing is lost on a reinstall and a future achievement can be
 * back-filled from history.
 *
 * Still carries the running no-bust streak ([seedStreak] primes it from the last
 * synced snapshot) for back-compat, but the server now derives the streak from
 * the explicit [PlayerStatHandSummary.busted] fact.
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
            busted = busted,
            startStack = context.humanStartingStack,
            endStack = context.humanEndingStack,
            bigBlind = context.bigBlind,
            potTotal = summary.totalPot,
            wasAllIn = summary.humanWasAllIn,
            wonByFold = summary.wonByFold,
            bustsDealt = if (summary.wonPot) context.bustedOpponentCount else 0,
            foldedWouldHaveLost = summary.foldedHandWouldHaveLost,
            // Only a showdown reveals a category (folds leave it null); HighCard
            // isn't a rewarded "show". The names match the server's ShownHand.
            handStrengthShown = summary.handCategory
                ?.takeIf { it != HandCategoryGrade.HighCard }
                ?.name,
            botDifficulty = context.botDifficulty,
        )
    }
}
