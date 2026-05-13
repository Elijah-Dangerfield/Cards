package com.dangerfield.cards.libraries.ui.components

import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import com.dangerfield.cards.libraries.ui.system.LocalColors
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalColors.current.accentPrimary.color,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
) {
    MaterialCircularProgressIndicator(
        modifier = modifier,
        color = color,
        strokeWidth = strokeWidth,
        trackColor = trackColor,
        strokeCap = strokeCap,
    )
}