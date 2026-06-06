package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_play_style_axis_aggressive
import cards.libraries.resources.generated.resources.ui_play_style_axis_loose
import cards.libraries.resources.generated.resources.ui_play_style_axis_passive
import cards.libraries.resources.generated.resources.ui_play_style_axis_tight
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Plots a player's poker style as a soft blob on a TIGHT↔LOOSE ×
 * PASSIVE↔AGGRESSIVE quadrant, with the style name in a pill and a
 * one-line description below. Built as a reusable shape so Stats, the
 * profile header, and the seat-tap sheet all read the same.
 *
 * [tightness] and [aggression] are normalised 0..1 (0 = loose / passive,
 * 1 = tight / aggressive) and clamped by the renderer. The four axis
 * labels are fixed for this chart type, so the component owns them; the
 * caller supplies only the position, name, and blurb.
 */
@Composable
fun PlayStyleBlob(
    tightness: Float,
    aggression: Float,
    styleName: String,
    description: String,
    modifier: Modifier = Modifier,
    gridSize: Dp = 140.dp,
    blobColor: ColorResource = AppTheme.colors.accentSecondary,
    onBlobColor: ColorResource = AppTheme.colors.onAccentSecondary,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimension.D400),
    ) {
        PlayStyleQuadrant(
            tightness = tightness,
            aggression = aggression,
            blobColor = blobColor,
            modifier = Modifier.size(gridSize),
        )
        StatusPill(
            text = styleName,
            background = blobColor,
            foreground = onBlobColor,
            typography = AppTheme.typography.Label.L400,
        )
        Text(
            text = description,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlayStyleQuadrant(
    tightness: Float,
    aggression: Float,
    blobColor: ColorResource,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = AppTheme.colors.border.color
    val labelColor = AppTheme.colors.contentTertiary.color
    val labelStyle = AppTheme.typography.Caption.C200.style
    val fill = blobColor.color
    val topLabel = stringResource(Res.string.ui_play_style_axis_aggressive)
    val bottomLabel = stringResource(Res.string.ui_play_style_axis_passive)
    val leftLabel = stringResource(Res.string.ui_play_style_axis_tight)
    val rightLabel = stringResource(Res.string.ui_play_style_axis_loose)

    Box(
        modifier = modifier
            .clip(Radii.R500.shape)
            .background(AppTheme.colors.surfaceRaised.color),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = size.minDimension * 0.18f
            val plotLeft = pad
            val plotTop = pad
            val plotW = size.width - 2f * pad
            val plotH = size.height - 2f * pad
            val cx = size.width / 2f
            val cy = size.height / 2f

            drawLine(
                color = gridColor,
                start = Offset(plotLeft, cy),
                end = Offset(plotLeft + plotW, cy),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = gridColor,
                start = Offset(cx, plotTop),
                end = Offset(cx, plotTop + plotH),
                strokeWidth = 1.dp.toPx(),
            )

            val t = tightness.coerceIn(0f, 1f)
            val a = aggression.coerceIn(0f, 1f)
            val point = Offset(
                x = plotLeft + (1f - t) * plotW,
                y = plotTop + (1f - a) * plotH,
            )
            val blobRadius = plotW * 0.30f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(fill.copy(alpha = 0.85f), fill.copy(alpha = 0f)),
                    center = point,
                    radius = blobRadius,
                ),
                radius = blobRadius,
                center = point,
            )
            drawCircle(color = fill, radius = 4.dp.toPx(), center = point)

            drawAxisLabel(textMeasurer, topLabel, Offset(cx, plotTop * 0.5f), labelColor, labelStyle)
            drawAxisLabel(
                textMeasurer,
                bottomLabel,
                Offset(cx, size.height - plotTop * 0.5f),
                labelColor,
                labelStyle,
            )
            drawAxisLabel(textMeasurer, leftLabel, Offset(plotLeft * 0.5f + 2f, cy), labelColor, labelStyle)
            drawAxisLabel(
                textMeasurer,
                rightLabel,
                Offset(size.width - plotLeft * 0.5f - 2f, cy),
                labelColor,
                labelStyle,
            )
        }
    }
}

private fun DrawScope.drawAxisLabel(
    textMeasurer: TextMeasurer,
    text: String,
    anchor: Offset,
    color: androidx.compose.ui.graphics.Color,
    style: TextStyle,
) {
    val measured = textMeasurer.measure(text = text, style = style.copy(color = color))
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            x = anchor.x - measured.size.width / 2f,
            y = anchor.y - measured.size.height / 2f,
        ),
    )
}

@Preview
@Composable
private fun PlayStyleBlobPreview_TightAggressive() {
    PreviewContent {
        PlayStyleBlob(
            tightness = 0.74f,
            aggression = 0.78f,
            styleName = "Tight-Aggressive",
            description = "You wait for strong hands, then bet them hard.",
        )
    }
}

@Preview
@Composable
private fun PlayStyleBlobPreview_LoosePassive() {
    PreviewContent {
        PlayStyleBlob(
            tightness = 0.22f,
            aggression = 0.28f,
            styleName = "Loose-Passive",
            description = "You see a lot of flops and tend to call rather than raise.",
            blobColor = AppTheme.colors.accentTertiary,
            onBlobColor = AppTheme.colors.onAccentTertiary,
        )
    }
}
