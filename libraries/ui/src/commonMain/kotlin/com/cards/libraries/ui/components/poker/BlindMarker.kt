package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Tiny circular chip showing a player's table role — Dealer (D), Small Blind
 * (SB), or Big Blind (BB). Renders nothing if none of those flags are set.
 *
 * Position the marker via the caller's modifier (typically anchored to the
 * bottom-end of the avatar Box with a small overlap inward — see room/impl
 * for the reference layout).
 */
@Composable
fun BlindMarker(
    isDealer: Boolean,
    isSmallBlind: Boolean,
    isBigBlind: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val role = when {
        isDealer -> Triple("D", PokerPalette.DealerWhite, AppTheme.colors.background)
        isSmallBlind -> Triple("SB", PokerPalette.ChipGold, AppTheme.colors.background)
        // White-on-red for the BB — black-on-red is too low contrast against
        // the dark red background to read at this size.
        isBigBlind -> Triple("BB", PokerPalette.BlindRed, AppTheme.colors.text)
        else -> null
    } ?: return
    val (label, bg, fg) = role
    // When clickable, the layout outer size grows to give the touch target
    // some room past the visible chip. Sized just generously enough to
    // distinguish "tap the marker" from "tap the avatar" or "tap the stack
    // number below" — going much bigger started claiming half the avatar's
    // tap zone and crowded the stack text below.
    if (onClick != null) {
        // Outer Box is intentionally NOT clipped — clipping it to a circle
        // would crop the inner chip (anchored at BottomEnd) wherever it pokes
        // past the circle's curve.
        Box(
            modifier = modifier
                .size(32.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.BottomEnd,
        ) {
            MarkerChip(label = label, bg = bg, fg = fg)
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            MarkerChip(label = label, bg = bg, fg = fg)
        }
    }
}

@Composable
private fun MarkerChip(
    label: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: com.dangerfield.cards.libraries.ui.system.color.ColorResource,
) {
    Box(
        modifier = Modifier
            .size(Dimension.D850)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L500,
            color = fg,
        )
    }
}

@Preview
@Composable
private fun BlindMarkerPreview_Dealer() {
    PreviewContent {
        BlindMarker(isDealer = true, isSmallBlind = false, isBigBlind = false)
    }
}

@Preview
@Composable
private fun BlindMarkerPreview_BigBlind() {
    PreviewContent {
        BlindMarker(isDealer = false, isSmallBlind = false, isBigBlind = true)
    }
}
