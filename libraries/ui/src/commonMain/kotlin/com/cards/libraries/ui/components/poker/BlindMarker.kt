package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
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
) {
    val (label, bg) = when {
        isDealer -> "D" to PokerPalette.DealerWhite
        isSmallBlind -> "SB" to PokerPalette.ChipGold
        isBigBlind -> "BB" to PokerPalette.BlindRed
        else -> null to null
    }
    if (label == null || bg == null) return
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.background,
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
