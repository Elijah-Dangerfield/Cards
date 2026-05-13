package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PaintingStyle.Companion.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.Radius

@Immutable
data class Border(val color: ColorResource, val width: Dp = StandardBorderWidth) {
    companion object {
        val Standard: Border
            @ReadOnlyComposable
            @Composable
            get() = Border(AppTheme.colors.border)
    }
}

val StandardBorderWidth = 1.dp

fun Modifier.border(border: Border): Modifier = this.border(
    width = border.width,
    color = border.color.color
)

@Composable
fun Modifier.dashedBorder(
    strokeWidth: Dp = 1.dp,
    dashSize: Float = 10f,
    gapSize: Float = dashSize,
    color: ColorResource = AppTheme.colors.border,
    radius: Radius = Radii.None,
) = drawWithCache {
    val strokeWidthPx = strokeWidth.toPx()
    val cornerRadiusPx = radius.cornerSize.toPx(size, this)

    onDrawWithContent {
        drawContent()

        drawRoundRect(
            color = color.color,
            style = Stroke(
                width = strokeWidthPx,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashSize, gapSize), 0f)
            ),
            cornerRadius = CornerRadius(cornerRadiusPx)
        )
    }
}