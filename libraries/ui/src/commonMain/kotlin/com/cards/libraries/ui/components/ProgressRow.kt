package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD100
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.Radii
import kotlin.math.roundToInt

enum class IndicatorAlignment {
    /** Align the center of the indicator with the progress point */
    Center,
    /** Align the leading edge of the indicator with the progress point */
    Leading,
    /** Align the trailing edge of the indicator with the progress point */
    Trailing
}

@Composable
fun ProgressRow(
    progressPercent: Float,
    animateChanges: Boolean = true,
    animationSpec: AnimationSpec<Float> = tween(durationMillis = 300, easing = FastOutSlowInEasing),
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    backgroundColor: ColorResource = AppTheme.colors.surfaceDisabled,
    progressColor: ColorResource = AppTheme.colors.accentPrimary,
    indicatorAlignment: IndicatorAlignment = IndicatorAlignment.Center,
    indicator: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit) = {}
) {
    val target = progressPercent.coerceIn(0f, 1f)

    val displayProgress by if (animateChanges) {
        animateFloatAsState(targetValue = target, animationSpec = animationSpec)
    } else {
        androidx.compose.runtime.rememberUpdatedState(target)
    }

    SubcomposeLayout(
        modifier = modifier
            .defaultMinSize(minHeight = Dimension.D500)
            .fillMaxWidth()
    ) { constraints ->
        val containerWidth = constraints.maxWidth

        val contentPlaceable = subcompose("content") {
            Box {
                ProvideTextConfig(
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content
                ) {
                    content()
                }
            }
        }.first().measure(constraints)

        val containerHeight = maxOf(
            contentPlaceable.height,
            constraints.minHeight
        )

        val indicatorPlaceable = indicator?.let {
            subcompose("indicator") { indicator() }
                .firstOrNull()
                ?.measure(Constraints())
        }

        val indicatorWidth = indicatorPlaceable?.width ?: 0
        val indicatorHeight = indicatorPlaceable?.height ?: 0

        val progressPx = (displayProgress * containerWidth).roundToInt()

        val indicatorX = when (indicatorAlignment) {
            IndicatorAlignment.Center -> progressPx - (indicatorWidth / 2)
            IndicatorAlignment.Leading -> progressPx
            IndicatorAlignment.Trailing -> progressPx - indicatorWidth
        }.coerceIn(0, (containerWidth - indicatorWidth).coerceAtLeast(0))

        val backgroundPlaceable = subcompose("background") {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(backgroundColor.color)
            )
        }.first().measure(
            constraints.copy(
                minWidth = containerWidth,
                maxWidth = containerWidth,
                minHeight = containerHeight,
                maxHeight = containerHeight
            )
        )

        val progressPlaceable = subcompose("progress") {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(progressColor.color)
            )
        }.first().measure(
            constraints.copy(
                minWidth = progressPx.coerceAtLeast(0),
                maxWidth = progressPx.coerceAtLeast(0),
                minHeight = containerHeight,
                maxHeight = containerHeight
            )
        )

        layout(containerWidth, containerHeight) {
            backgroundPlaceable.place(0, 0)
            progressPlaceable.place(0, 0)
            contentPlaceable.place(0, 0)

            indicatorPlaceable?.place(
                x = indicatorX,
                y = (containerHeight - indicatorHeight) / 2
            )
        }
    }
}

@Composable
fun RoundedIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Dimension.D600)
            .background(
                color = AppTheme.colors.surface.color,
                shape = CircleShape
            )
    )
}

@Composable
fun LineIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = Dimension.D100, height = Dimension.D800)
            .background(AppTheme.colors.surface.color)
    )
}

@Preview
@Composable
fun ProgressRowPreview() {
    PreviewContent {
        Column {
            Text("No Indicator - Rounded")
            ProgressRow(
                progressPercent = 0.5f,
                shape = Radii.R400.shape
            ) {
                Text("Hello")
            }

            VerticalSpacerD100()

            Text("Circle - Center Aligned - Rounded")
            ProgressRow(
                progressPercent = 0.5f,
                shape = Radii.R400.shape,
                indicatorAlignment = IndicatorAlignment.Center,
                indicator = { RoundedIndicator() }
            ) {
                Text("50% Progress")
            }

            VerticalSpacerD100()

            Text("Circle - Trailing Edge")
            ProgressRow(
                progressPercent = 0.75f,
                shape = Radii.R400.shape,
                indicatorAlignment = IndicatorAlignment.Trailing,
                indicator = { RoundedIndicator() }
            ) {
                Text("75% Progress")
            }

            VerticalSpacerD100()

            Text("Line - Leading Edge")
            ProgressRow(
                progressPercent = 0.3f,
                shape = Radii.R400.shape,
                indicatorAlignment = IndicatorAlignment.Leading,
                indicator = { LineIndicator() }
            ) {
                Text("30% Progress")
            }

            VerticalSpacerD100()

            Text("Line - Center Aligned")
            ProgressRow(
                progressPercent = 0.9f,
                shape = Radii.R400.shape,
                indicatorAlignment = IndicatorAlignment.Center,
                indicator = { LineIndicator() }
            ) {
                Text("90% Progress")
            }
        }
    }
}

@Preview
@Composable
fun ProgressRowPreviewNoContent() {
    PreviewContent {
        Column {


            ProgressRow(progressPercent = 0.3f)
            VerticalSpacerD100()
            ProgressRow(
                progressPercent = 0.3f,
                shape = Radii.R400.shape,
                indicator = { RoundedIndicator() }
            )
            VerticalSpacerD100()
            ProgressRow(
                progressPercent = 0.6f,
                shape = Radii.R400.shape,
                indicatorAlignment = IndicatorAlignment.Leading,
                indicator = { LineIndicator() }
            )
        }
    }
}