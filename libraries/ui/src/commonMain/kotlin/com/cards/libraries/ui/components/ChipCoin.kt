package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
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
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Gold casino-chip icon — the one canonical way to render "this is chips" in
 * UI. Solid [PokerPalette.ChipGold] circle with a "$" sigil inside.
 *
 * Use this anywhere a chip count, balance, or cost is rendered next to a
 * number. The whole point is that **the chip icon looks identical** on the
 * shop, table, balance pills, pot, and stack — so users build an immediate
 * mental shortcut: "gold circle = chips."
 *
 * Sizing: pick a [size] that roughly matches the line height of the text
 * sitting next to it. [textTypography] controls the "$" sigil only; pick a
 * scale that visually balances the chosen [size] (a 56dp coin doesn't want
 * a Body.B400 dollar sign).
 *
 * Prefer this over inlining the gold-circle-with-dollar pattern. Replaces
 * also: the grey "🪙" emoji used in the shop redesign, which renders
 * inconsistently across platforms and reads as tarnished copper on dark
 * surfaces.
 */
@Composable
fun ChipCoin(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    textTypography: TypographyResource = AppTheme.typography.Body.B400,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(PokerPalette.ChipGold),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$",
            typography = textTypography,
            color = AppTheme.colors.background,
        )
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
    color: ColorResource = AppTheme.colors.text,
    gap: Dp = 6.dp,
    coinSymbolTypography: TypographyResource = coinSymbolTypographyFor(coinSize),
    formatter: (Long) -> String = ::formatThousands,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipCoin(size = coinSize, textTypography = coinSymbolTypography)
        Spacer(modifier = Modifier.width(gap))
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

/**
 * Pick a sensible "$" sigil scale for a given coin diameter. Keeps callers
 * from having to reason about the inner typography for the common case while
 * still letting them override [ChipCoinAmount.coinSymbolTypography] for the
 * outlier cases.
 */
@Composable
private fun coinSymbolTypographyFor(size: Dp): TypographyResource = when {
    size <= 16.dp -> AppTheme.typography.Body.B400
    size <= 22.dp -> AppTheme.typography.Body.B400
    size <= 32.dp -> AppTheme.typography.Body.B600
    size <= 48.dp -> AppTheme.typography.Heading.H700
    else -> AppTheme.typography.Heading.H800
}

@Preview
@Composable
private fun ChipCoinPreview_SizeScale() {
    PreviewContent {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipCoin(size = 14.dp, textTypography = AppTheme.typography.Body.B400)
            Spacer(modifier = Modifier.width(8.dp))
            ChipCoin()
            Spacer(modifier = Modifier.width(8.dp))
            ChipCoin(size = 28.dp, textTypography = AppTheme.typography.Body.B600)
            Spacer(modifier = Modifier.width(8.dp))
            ChipCoin(size = 48.dp, textTypography = AppTheme.typography.Heading.H700)
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
