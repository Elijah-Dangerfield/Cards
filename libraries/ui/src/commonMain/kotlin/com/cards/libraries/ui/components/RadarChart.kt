package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One spoke on a [RadarChart]. `value` is clamped to 0..1 by the renderer —
 * the chart always normalises to a full ring, so callers don't have to
 * pre-scale per-axis maxes themselves.
 */
data class RadarAxis(
    val label: String,
    val value: Float,
)

/**
 * Small N-axis radar / spider chart for visualising a handful of normalised
 * attributes (0..1 each) as a filled polygon. Axes are evenly spaced around
 * the circle starting from straight up.
 *
 * Three rings are drawn for context (33%, 67%, 100%); the foreground polygon
 * is filled + stroked in [foregroundColor]. Axis labels render outside each
 * spoke tip and don't participate in the polygon math, so the chart stays
 * legible at small sizes (~96-128 dp) which is the intended use — an
 * at-a-glance shape next to a one-line label, not a data-rich dashboard.
 */
@Composable
fun RadarChart(
    axes: List<RadarAxis>,
    modifier: Modifier = Modifier,
    chartSize: Dp = 112.dp,
    foregroundColor: Color = AppTheme.colors.accentPrimary.color,
    gridColor: Color = AppTheme.colors.borderStrong.color,
    labelColor: Color = AppTheme.colors.contentDisabled.color,
    labelStyle: TextStyle = AppTheme.typography.Caption.C300.style,
    fillAlpha: Float = 0.30f,
    showLabels: Boolean = true,
) {
    if (axes.size < 3) return
    val textMeasurer = rememberTextMeasurer()
    val normalisedAxes = remember(axes) {
        axes.map { it.copy(value = it.value.coerceIn(0f, 1f)) }
    }

    Box(modifier = modifier.size(chartSize)) {
        Canvas(modifier = Modifier.size(chartSize)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            // Reserve an outer ring for labels only when we're drawing them; a
            // label-less mark (e.g. the profile teaser) gets to fill its bounds.
            val labelPad = if (showLabels) size.minDimension * 0.18f else 0f
            val radius = (size.minDimension / 2f) - labelPad

            drawRadarRings(center, radius, gridColor, ringFractions = listOf(0.33f, 0.67f, 1.0f))
            drawRadarSpokes(center, radius, normalisedAxes.size, gridColor)
            drawRadarPolygon(center, radius, normalisedAxes, foregroundColor, fillAlpha)
            if (showLabels) {
                drawRadarLabels(
                    center = center,
                    radius = radius,
                    labels = normalisedAxes.map { it.label },
                    textMeasurer = textMeasurer,
                    labelColor = labelColor,
                    labelStyle = labelStyle,
                    outerPad = labelPad,
                )
            }
        }
    }
}

private fun DrawScope.drawRadarRings(
    center: Offset,
    radius: Float,
    color: Color,
    ringFractions: List<Float>,
) {
    val stroke = Stroke(width = 1.dp.toPx())
    ringFractions.forEach { fraction ->
        drawCircle(
            color = color,
            radius = radius * fraction,
            center = center,
            style = stroke,
        )
    }
}

private fun DrawScope.drawRadarSpokes(
    center: Offset,
    radius: Float,
    axisCount: Int,
    color: Color,
) {
    val strokeWidth = 1.dp.toPx()
    repeat(axisCount) { i ->
        val angle = angleFor(i, axisCount)
        val end = Offset(
            x = center.x + radius * cos(angle).toFloat(),
            y = center.y + radius * sin(angle).toFloat(),
        )
        drawLine(color = color, start = center, end = end, strokeWidth = strokeWidth)
    }
}

private fun DrawScope.drawRadarPolygon(
    center: Offset,
    radius: Float,
    axes: List<RadarAxis>,
    color: Color,
    fillAlpha: Float,
) {
    val path = Path()
    axes.forEachIndexed { i, axis ->
        val angle = angleFor(i, axes.size)
        val r = radius * axis.value
        val point = Offset(
            x = center.x + r * cos(angle).toFloat(),
            y = center.y + r * sin(angle).toFloat(),
        )
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path = path, color = color.copy(alpha = fillAlpha))
    drawPath(path = path, color = color, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawRadarLabels(
    center: Offset,
    radius: Float,
    labels: List<String>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelColor: Color,
    labelStyle: TextStyle,
    outerPad: Float,
) {
    val labelRadius = radius + (outerPad * 0.5f)
    labels.forEachIndexed { i, text ->
        val angle = angleFor(i, labels.size)
        val measured = textMeasurer.measure(text = text, style = labelStyle.copy(color = labelColor))
        val anchor = Offset(
            x = center.x + labelRadius * cos(angle).toFloat(),
            y = center.y + labelRadius * sin(angle).toFloat(),
        )
        val origin = Offset(
            x = anchor.x - measured.size.width / 2f,
            y = anchor.y - measured.size.height / 2f,
        )
        drawText(
            textLayoutResult = measured,
            topLeft = origin,
        )
    }
}

private fun angleFor(index: Int, axisCount: Int): Double =
    -PI / 2.0 + (2.0 * PI * index / axisCount)

// ---- Previews ------------------------------------------------------------------------------

@Preview
@Composable
private fun RadarChartPreview_Spread() {
    PreviewContent {
        RadarChart(
            axes = listOf(
                RadarAxis("Tight", 0.78f),
                RadarAxis("Aggro", 0.25f),
                RadarAxis("Bluff", 0.10f),
                RadarAxis("Patient", 0.75f),
            ),
        )
    }
}

@Preview
@Composable
private fun RadarChartPreview_Maniac() {
    PreviewContent {
        RadarChart(
            axes = listOf(
                RadarAxis("Tight", 0.20f),
                RadarAxis("Aggro", 0.90f),
                RadarAxis("Bluff", 0.75f),
                RadarAxis("Patient", 0.10f),
            ),
        )
    }
}
