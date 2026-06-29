package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The seat's position marker — Dealer (`D` circle), Small Blind (`SB` pill), or
 * Big Blind (`BB` pill). A white "button" with a dark cut border so it reads
 * against the felt; greys (via [muted]) when the seat is folded or busted.
 * Renders nothing if none of the role flags are set.
 *
 * Position the marker via the caller's modifier (the table anchors it to the
 * avatar's top-left corner — see room/impl for the reference layout).
 */
@Composable
fun BlindMarker(
    isDealer: Boolean,
    isSmallBlind: Boolean,
    isBigBlind: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val label = when {
        isDealer -> "D"
        isSmallBlind -> "SB"
        isBigBlind -> "BB"
        else -> return
    }
    val bg = if (muted) AppTheme.colors.contentTertiary.color else AppTheme.colors.poker.dealerWhite.color
    val shape = if (isDealer) CircleShape else Radii.Round.shape
    val base = modifier
        .clip(shape)
        .background(bg)
        .border(width = 1.5.dp, color = AppTheme.colors.background.color, shape = shape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(
        modifier = if (isDealer) {
            base.size(Dimension.D850)
        } else {
            base.padding(horizontal = 5.dp, vertical = 1.dp)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L500,
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

@Preview
@Composable
private fun BlindMarkerPreview_SmallBlindMuted() {
    PreviewContent {
        BlindMarker(isDealer = false, isSmallBlind = true, isBigBlind = false, muted = true)
    }
}
