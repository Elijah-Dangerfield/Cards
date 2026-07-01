package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

@Composable
fun ChipBadge(
    amount: Long?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * Replay trigger for the odometer — when [revealKey] flips, the count rolls
     * from [revealFrom] to [amount]. Home uses this to animate a balance change
     * that landed while the user was on another screen; other callers leave it null.
     */
    revealFrom: Long? = null,
    revealKey: Any? = null,
    /**
     * True while a wallet reconcile is in flight — the balance shown is a
     * pre-settlement guess the server hasn't confirmed. Dims the count and shows
     * an inline spinner so the number reads as "updating" rather than final.
     * Ignored while [amount] is null (that's the un-hydrated placeholder).
     */
    isReconciling: Boolean = false,
) {
    LeadingPill(
        modifier = modifier,
        onClick = onClick,
        // The wallet is a prominent affordance — grow it past the family default.
        contentPadding = PaddingValues(horizontal = Dimension.D500, vertical = Dimension.D400),
        leading = { ChipCoin(size = 28.dp) },
        trailing = {
            if (amount == null) {
                // Null = local Room hasn't emitted yet (first-launch /
                // post-wipe). Render a placeholder rather than "0" so
                // the user doesn't see a momentary "you have no chips"
                // flash before sync lands.
                Text(
                    text = "—",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.contentSecondary,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedNumberText(
                        value = amount,
                        typography = AppTheme.typography.Heading.H600,
                        // Dim while settling so a stale-then-corrected value doesn't
                        // read as final; back to full content color once confirmed.
                        color = if (isReconciling) {
                            AppTheme.colors.contentSecondary
                        } else {
                            AppTheme.colors.content
                        },
                        formatter = { formatThousands(it) },
                        // Flash green when the wallet grows and red when it shrinks —
                        // the post-game balance change reads as a win or a loss at a
                        // glance, then settles back to the normal colour.
                        gainColor = AppTheme.colors.success,
                        lossColor = AppTheme.colors.danger,
                        revealFrom = revealFrom,
                        revealKey = revealKey,
                    )
                    if (isReconciling) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = Dimension.D200)
                                .size(14.dp),
                            color = AppTheme.colors.contentSecondary.color,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Compact chip / number formatter for tight UI surfaces.
 *
 * - `123`     → `"123"`
 * - `1_234`   → `"1.2k"`
 * - `12_345`  → `"12k"`
 * - `1_234_567` → `"1.2M"`
 *
 * Use this anywhere a number is rendered in a fixed-width pill / badge / tile
 * where a comma-grouped value (e.g. `"1,234,567"`) can blow out the layout.
 */
fun formatCompactChips(value: Long): String {
    val abs = if (value < 0) -value else value
    return when {
        abs < 1_000L -> value.toString()
        abs < 10_000L -> {
            // 1.2k — one fractional digit, dropped if zero
            val tenths = (value * 10) / 1_000L
            val whole = tenths / 10
            val frac = (tenths % 10).let { if (it < 0) -it else it }
            if (frac == 0L) "${whole}k" else "${whole}.${frac}k"
        }
        abs < 1_000_000L -> "${value / 1_000L}k"
        abs < 10_000_000L -> {
            val tenths = (value * 10) / 1_000_000L
            val whole = tenths / 10
            val frac = (tenths % 10).let { if (it < 0) -it else it }
            if (frac == 0L) "${whole}M" else "${whole}.${frac}M"
        }
        else -> "${value / 1_000_000L}M"
    }
}
