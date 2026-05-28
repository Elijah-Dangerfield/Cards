package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.runtime.Composable
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_earned_ago_days
import cards.libraries.resources.generated.resources.ui_earned_ago_months
import cards.libraries.resources.generated.resources.ui_earned_ago_today
import cards.libraries.resources.generated.resources.ui_earned_ago_weeks
import cards.libraries.resources.generated.resources.ui_earned_ago_years
import cards.libraries.resources.generated.resources.ui_earned_ago_yesterday
import org.jetbrains.compose.resources.stringResource

/**
 * Relative-time bucket for an achievement's earned-at timestamp, derived
 * from a pure factory so callers in non-Composable contexts (tests, log
 * formatters) can build the same shape as the renderer. Lift mirrors the
 * `tenureSpecs(...)` / `BotPlayingStyle` migrations earlier in the
 * strings-sweep — the sealed family carries resource ids + numeric args;
 * the [label] extension resolves them in a `@Composable` context.
 */
sealed class EarnedAgo {
    data object Today : EarnedAgo()
    data object Yesterday : EarnedAgo()
    data class DaysAgo(val days: Int) : EarnedAgo()
    data class WeeksAgo(val weeks: Int) : EarnedAgo()
    data class MonthsAgo(val months: Int) : EarnedAgo()
    data class YearsAgo(val years: Int) : EarnedAgo()
}

private const val DayMs: Long = 24L * 60L * 60L * 1000L

/**
 * Pure factory — `nowEpochMs` is a parameter (not [kotlin.time.Clock])
 * so tests pin a fixed clock. Same `(now - earned).coerceAtLeast(0L)`
 * clamp the prior `formatEarnedAgo` carried, so future earned-at values
 * (clock skew) bucket as [EarnedAgo.Today] rather than negative days.
 */
fun earnedAgo(earnedAtEpochMs: Long, nowEpochMs: Long): EarnedAgo {
    val days = ((nowEpochMs - earnedAtEpochMs).coerceAtLeast(0L)) / DayMs
    return when {
        days == 0L -> EarnedAgo.Today
        days == 1L -> EarnedAgo.Yesterday
        days < 7L -> EarnedAgo.DaysAgo(days.toInt())
        days < 30L -> EarnedAgo.WeeksAgo((days / 7L).toInt())
        days < 365L -> EarnedAgo.MonthsAgo((days / 30L).toInt())
        else -> EarnedAgo.YearsAgo((days / 365L).toInt())
    }
}

@Composable
fun EarnedAgo.label(): String = when (this) {
    EarnedAgo.Today -> stringResource(Res.string.ui_earned_ago_today)
    EarnedAgo.Yesterday -> stringResource(Res.string.ui_earned_ago_yesterday)
    is EarnedAgo.DaysAgo -> stringResource(Res.string.ui_earned_ago_days, days)
    is EarnedAgo.WeeksAgo -> stringResource(Res.string.ui_earned_ago_weeks, weeks)
    is EarnedAgo.MonthsAgo -> stringResource(Res.string.ui_earned_ago_months, months)
    is EarnedAgo.YearsAgo -> stringResource(Res.string.ui_earned_ago_years, years)
}
