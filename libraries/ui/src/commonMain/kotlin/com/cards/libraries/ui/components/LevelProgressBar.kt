package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.LevelProgressGradient
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Horizontal progress bar painted with the cyan-to-green
 * [LevelProgressGradient] over a [surfacePrimary][AppTheme.colors.surface]
 * track. The shared visual for any "X of Y XP" / "level progress"
 * surface — Profile's level summary, Stats's level pill, future
 * progression banners.
 *
 * Not a swap-in for the generic [LinearProgressIndicator]: that one
 * takes a solid `Color` (Material3 underneath) and is right for any
 * non-progression progress (download, sync, etc.). This one bakes in
 * the gradient brush and the rounded-cap track so every level surface
 * reads identically.
 *
 * [fraction] is coerced into `[0, 1]` so a downstream miscalc (negative
 * XP, fraction >1 from a stale rollover) never escapes layout. Default
 * [height] is the Profile-summary value (8dp); Stats can pass 10dp
 * for the slightly chunkier hero treatment.
 */
@Composable
fun LevelProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    trackColor: ColorResource = AppTheme.colors.surface,
    progressBrush: Brush = LevelProgressGradient,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(Radii.Round.shape)
            .background(trackColor.color),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(Radii.Round.shape)
                .background(progressBrush),
        )
    }
}

@Preview
@Composable
private fun LevelProgressBarPreview_HalfFull() {
    PreviewContent {
        LevelProgressBar(fraction = 0.5f)
    }
}

@Preview
@Composable
private fun LevelProgressBarPreview_NearlyFull_Tall() {
    PreviewContent {
        LevelProgressBar(fraction = 0.9f, height = 10.dp)
    }
}

@Preview
@Composable
private fun LevelProgressBarPreview_Empty() {
    PreviewContent {
        LevelProgressBar(fraction = 0f)
    }
}
