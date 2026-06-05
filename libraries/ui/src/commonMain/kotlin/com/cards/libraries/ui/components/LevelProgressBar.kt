package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii

/**
 * Horizontal "two-tone arcade" level/XP bar. The filled portion mirrors the
 * design-system button's 3D lip: a lighter [faceColor] sits on top of a
 * darker [deepColor] band, so the fill reads as a chunky, slightly raised
 * arcade chip rather than a flat gradient. It sits in a recessed
 * [trackColor] channel with fully-rounded caps.
 *
 * The shared visual for any "X of Y XP" / "level progress" surface —
 * Profile's level summary, Stats's level pill, future progression banners.
 *
 * Not a swap-in for the generic [LinearProgressIndicator]: that one takes a
 * solid `Color` (Material3 underneath) and is right for any non-progression
 * progress (download, sync, etc.). This one bakes in the arcade fill + the
 * rounded-cap track so every level surface reads identically.
 *
 * [fraction] is coerced into `[0, 1]` so a downstream miscalc (negative XP,
 * fraction >1 from a stale rollover) never escapes layout. The default
 * [height] is tuned to read as a chunky arcade bar on the Profile + Stats
 * level summaries; pass a smaller value for tighter surfaces.
 *
 * [progressBrush] is an optional override for the face fill — pass a [Brush]
 * (e.g. a gradient) when a surface wants a custom treatment; the [deepColor]
 * lip stays underneath either way.
 */
@Composable
fun LevelProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    trackColor: ColorResource = AppTheme.colors.surfaceHigh,
    faceColor: ColorResource = AppTheme.colors.accentPrimary,
    deepColor: ColorResource = AppTheme.colors.accentPrimaryDeep,
    progressBrush: Brush? = null,
) {
    // The dark lip exposed at the bottom of the fill. Scales with height so
    // the two-tone read survives both the 8dp and 10dp treatments, with a
    // floor so very short bars still show a sliver of deep.
    val lip = (height * 0.34f).coerceAtLeast(2.dp)
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
                .clip(Radii.Round.shape),
        ) {
            // Deep band fills the whole fill area; the face drops onto it,
            // leaving `lip` of deep showing along the bottom edge.
            Box(Modifier.matchParentSize().background(deepColor.color))
            val face = Modifier
                .matchParentSize()
                .padding(bottom = lip)
                .clip(Radii.Round.shape)
            Box(
                modifier = if (progressBrush != null) {
                    face.background(progressBrush)
                } else {
                    face.background(faceColor.color)
                },
            )
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LevelProgressBarPreview_HalfFull() {
    PreviewContent {
        LevelProgressBar(fraction = 0.5f)
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LevelProgressBarPreview_NearlyFull_Tall() {
    PreviewContent {
        LevelProgressBar(fraction = 0.9f, height = 10.dp)
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LevelProgressBarPreview_Empty() {
    PreviewContent {
        LevelProgressBar(fraction = 0f)
    }
}
