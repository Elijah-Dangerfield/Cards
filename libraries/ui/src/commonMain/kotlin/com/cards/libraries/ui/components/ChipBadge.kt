package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

@Composable
fun ChipBadge(
    amount: Long?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    LeadingPill(
        modifier = modifier,
        onClick = onClick,
        leading = { ChipCoin() },
        trailing = {
            if (amount == null) {
                // Null = local Room hasn't emitted yet (first-launch /
                // post-wipe). Render a placeholder rather than "0" so
                // the user doesn't see a momentary "you have no chips"
                // flash before sync lands.
                Text(
                    text = "—",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                )
            } else {
                AnimatedNumberText(
                    value = amount,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.text,
                    formatter = { formatThousands(it) },
                )
            }
        },
    )
}

internal fun formatThousands(value: Long): String {
    val s = value.toString()
    val sb = StringBuilder()
    val len = s.length
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
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
