package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.border
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.typography.TypographyResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Gold casino-chip icon — the one canonical way to render "this is chips" in
 * UI. Solid [PokerColors.chipGold] circle with a "$" sigil inside.
 *
 * Use this anywhere a chip count, balance, or cost is rendered next to a
 * number. The whole point is that **the chip icon looks identical** on the
 * shop, table, balance pills, pot, and stack — so users build an immediate
 * mental shortcut: "gold circle = chips."
 *
 * Sizing: pick a [size] that roughly matches the line height of the text
 * sitting next to it; the "$" sigil scales to the coin automatically. Pass
 * [showSymbol] = false for the tightest surfaces where a centered "$" reads
 * cramped (a bare gold dot still says "chips").
 *
 * Prefer this over inlining the gold-circle pattern.
 */
@Composable
fun ChipCoin(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    showSymbol: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AppTheme.colors.poker.chipGold.color)
            .border(
                when (size) {
                    18.dp -> 1.dp
                    28.dp -> 2.dp
                    48.dp -> 3.dp
                    else -> 0.dp
                }, AppTheme.colors.poker.chipGoldOutline.color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (showSymbol) {
            Text(
                text = "$",
                typography = coinSymbolTypographyFor(size),
                color = AppTheme.colors.background,
            )
        }
    }
}

/**
 * Convenience: a chip coin + a number, separated by a small gap. Default for
 * inline price/cost/balance rendering where the caller doesn't need fine
 * control over the inner Row.
 *
 * The amount goes through [formatter]; defaults to thousands-separated for
 * roomy surfaces (shop, balance pills). Tight surfaces — a player's stack
 * tile, an in-line table HUD — should pass `::formatCompactChips` so the
 * "$1.2k" form keeps the layout from stretching.
 */
@Composable
fun ChipCoinAmount(
    amount: Long,
    modifier: Modifier = Modifier,
    coinSize: Dp = 18.dp,
    typography: TypographyResource = AppTheme.typography.Body.B500,
    color: ColorResource = AppTheme.colors.content,
    gap: Dp = 6.dp,
    formatter: (Long) -> String = ::formatThousands,
    animated: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipCoin(size = coinSize)
        Spacer(modifier = Modifier.width(gap))
        if (animated) {
            AnimatedNumberText(
                value = amount,
                typography = typography,
                color = color,
                formatter = formatter,
            )
        } else {
            Text(
                text = formatter(amount),
                typography = typography,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pick a "$" sigil scale for a given coin diameter so the sigil stays balanced
 * from a 12dp inline coin up to a 96dp hero coin, without callers reasoning
 * about the inner typography.
 */
@Composable
private fun coinSymbolTypographyFor(size: Dp): TypographyResource = when {
    size <= Dimension.D700 -> AppTheme.typography.Body.B400
    size <= Dimension.D1050 -> AppTheme.typography.Body.B600
    size <= Dimension.D1300 -> AppTheme.typography.Heading.H700
    else -> AppTheme.typography.Heading.H800
}

@Preview
@Composable
private fun ChipCoinPreview_SizeScale() {
    PreviewContent {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipCoin(size = 14.dp)
            Spacer(modifier = Modifier.width(8.dp))
            ChipCoin()
            Spacer(modifier = Modifier.width(8.dp))
            ChipCoin(size = 28.dp)
            Spacer(modifier = Modifier.width(8.dp))
            ChipCoin(size = 48.dp)
        }
    }
}

@Preview
@Composable
private fun ChipCoinAmountPreview() {
    PreviewContent {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipCoinAmount(amount = 2_500)
            Spacer(modifier = Modifier.width(16.dp))
            ChipCoinAmount(
                amount = 25_000,
                coinSize = 28.dp,
                typography = AppTheme.typography.Heading.H700,
            )
        }
    }
}
