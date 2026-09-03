package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.inspectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.Radius
import com.dangerfield.cards.system.color.ProvideContentColor
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.system.thenIfNotNull
import com.dangerfield.cards.libraries.ui.Border
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.inset
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.text.Text
import androidx.compose.ui.tooling.preview.Preview

@Composable
@NonRestartableComposable
fun Surface(
    color: ColorResource?,
    contentColor: ColorResource,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    radius: Radius = Radii.Default,
    elevation: Elevation = Elevation.None,
    border: Border? = null,
    alpha: Float = 1f,
    onClick: () -> Unit,
    bounceScale: Float = 0.95f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    indication: Indication? = null,
    role: Role? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Raw-Color escape hatch for backgrounds that don't map to a DS token
    // (e.g. per-equipped-felt accent surfaces driven from a CompositionLocal).
    // Wins over `color` when non-null; default path stays type-safe.
    colorOverride: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .thenIfNotNull(role) {
                semantics {
                    this.role = it
                }
            }
            .background(
                color = color,
                shape = radius.shape,
                elevation = elevation,
                clip = true,
                alpha = alpha,
                border = border,
                colorOverride = colorOverride,
            )
            .padding(contentPadding)
            .bounceClick(
                enabled = enabled,
                scaleDown = bounceScale,
                indication = indication,
                mutableInteractionSource = interactionSource,
                onClick = onClick,
            ),
        propagateMinConstraints = true
    ) {
        ProvideContentColor(contentColor, content)
    }
}

@Composable
@NonRestartableComposable
fun Surface(
    color: ColorResource?,
    contentColor: ColorResource,
    modifier: Modifier = Modifier,
    radius: Radius = Radii.Default,
    elevation: Elevation = Elevation.None,
    border: Border? = null,
    alpha: Float = 1f,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Raw-Color escape hatch for backgrounds that don't map to a DS token
    // (e.g. per-equipped-felt accent surfaces driven from a CompositionLocal).
    // Wins over `color` when non-null; default path stays type-safe.
    colorOverride: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                color = color,
                shape = radius.shape,
                elevation = elevation,
                clip = true,
                alpha = alpha,
                border = border,
                colorOverride = colorOverride,
            )
            .semantics(mergeDescendants = false) {
                isTraversalGroup = true
            }
            // This prevents siblings that are underneath this surface from being receiving pointer events
            .pointerInput(Unit) {}
            .padding(contentPadding),
        propagateMinConstraints = true
    ) {
        ProvideContentColor(contentColor, content)
    }
}

private fun Modifier.background(
    color: ColorResource?,
    shape: Shape,
    elevation: Elevation,
    clip: Boolean,
    alpha: Float,
    border: Border?,
    colorOverride: androidx.compose.ui.graphics.Color? = null,
): Modifier = inspectable(
    androidx.compose.ui.platform.debugInspectorInfo {
        name = "background"
        properties["color"] = color
        properties["colorOverride"] = colorOverride
        properties["shape"] = shape
        properties["elevation"] = elevation.dp
        properties["clip"] = clip
        properties["alpha"] = alpha
        properties["border"] = border
    }
) {
    val backgroundShape = if (border == null || border.color.color.alpha < 0.99f) shape else shape.inset(border.width / 2f)
    val effectiveColor: androidx.compose.ui.graphics.Color? = colorOverride ?: color?.color
    this
        .thenIf(elevation > Elevation.None || alpha < 1f) {
            graphicsLayer {
                if (elevation > Elevation.None) {
                    shadowElevation = elevation.dp.toPx()
                    spotShadowColor = ColorResource.Black.color
                    ambientShadowColor = ColorResource.Black.color
                }
                this.alpha = alpha
                this.shape = shape
            }
        }
        .thenIfNotNull(border) {
            this.border(width = it.width, color = it.color.color, shape = shape)
        }
        .thenIfNotNull(effectiveColor) {
            this.background(color = it, shape = shape)
        }
        .thenIf(clip) { clip(backgroundShape) }
}

@Preview
@Composable
private fun SurfacePreview() {
    PreviewContent {
        Surface(
            color = AppTheme.colors.background,
            contentColor = AppTheme.colors.content,
            contentPadding = PaddingValues(Dimension.D900)
        ) {
            Text("Hello")
        }
    }
}

@Preview
@Composable
private fun ClickableSurfacePreview() {
    PreviewContent {
        Surface(
            color = AppTheme.colors.background,
            contentColor = AppTheme.colors.content,
            radius = Radii.Banner,
            contentPadding = PaddingValues(Dimension.D900)
        ) {
            Text("Hello")
        }
    }
}

@Preview
@Composable
private fun ClickableSurfacePreviewNoColor() {
    PreviewContent(backgroundColor = null) {
        Surface(
            color = null,
            contentColor = AppTheme.colors.content,
            radius = Radii.Banner,
            contentPadding = PaddingValues(Dimension.D900)
        ) {
            Text("Hello")
        }
    }
}
