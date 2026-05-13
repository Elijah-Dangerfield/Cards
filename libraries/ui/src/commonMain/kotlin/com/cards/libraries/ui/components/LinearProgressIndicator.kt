package com.dangerfield.cards.libraries.ui.components

import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.dangerfield.cards.libraries.ui.system.LocalColors

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float,
    color: Color = LocalColors.current.accentPrimary.color,
    trackColor: Color = ProgressIndicatorDefaults.circularTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
) {
    androidx.compose.material3.LinearProgressIndicator(
        modifier = modifier,
        color = color,
        progress = progress,
        trackColor = trackColor,
        strokeCap = strokeCap,
    )
}