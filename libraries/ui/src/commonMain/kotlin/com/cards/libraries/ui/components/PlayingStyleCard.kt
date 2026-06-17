package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The shared "playing style" card: a style name + one-line description beside a
 * small [RadarChart] of the four play-style axes (Tight / Aggro / Bluff /
 * Patient). One surface, used by the seat-tap player profile, the Stats page,
 * and — as a compact label-less [PlayStyleRadarMark] — the profile teaser, so
 * every play-style readout reads as the same thing.
 *
 * [axes] are the four normalised attributes; the caller derives them (from a
 * bot personality, or the human's stats). [color] defaults to the progression
 * cyan so the chart matches the rest of the progression surfaces.
 */
@Composable
fun PlayingStyleCard(
    axes: List<RadarAxis>,
    styleName: String,
    description: String,
    modifier: Modifier = Modifier,
    color: ColorResource = AppTheme.colors.poker.progressionCyan,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            .padding(Dimension.D500),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = styleName,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                )
                VerticalSpacerD200()
                Text(
                    text = description,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Spacer(modifier = Modifier.width(Dimension.D400))
            RadarChart(
                axes = axes,
                foregroundColor = color.color,
            )
        }
    }
}

/**
 * Compact, label-less [RadarChart] with a fixed example shape — a decorative
 * "your style at a glance" mark (e.g. the profile stats banner) that reads as
 * the same radar as [PlayingStyleCard] without needing real data or labels.
 */
@Composable
fun PlayStyleRadarMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    color: ColorResource = AppTheme.colors.poker.progressionCyan,
) {
    RadarChart(
        axes = ExampleStyleAxes,
        modifier = modifier,
        chartSize = size,
        foregroundColor = color.color,
        showLabels = false,
    )
}

private val ExampleStyleAxes = listOf(
    RadarAxis(label = "Tight", value = 0.74f),
    RadarAxis(label = "Aggro", value = 0.78f),
    RadarAxis(label = "Bluff", value = 0.45f),
    RadarAxis(label = "Patient", value = 0.30f),
)

@Preview
@Composable
private fun PlayingStyleCardPreview() {
    PreviewContent {
        PlayingStyleCard(
            axes = ExampleStyleAxes,
            styleName = "Tight-Aggressive",
            description = "You wait for strong hands, then bet them hard.",
        )
    }
}
