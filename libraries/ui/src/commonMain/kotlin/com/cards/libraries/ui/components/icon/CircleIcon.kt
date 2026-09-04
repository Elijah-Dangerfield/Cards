package com.dangerfield.cards.libraries.ui.components.icon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.Surface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun CircleIcon(
    icon: IconResource?,
    iconSize: IconSize,
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
    backgroundColor: ColorResource = AppTheme.colors.surface,
    contentColor: ColorResource = AppTheme.colors.content,
    elevation: Elevation = Elevation.None,
    onClick: (() -> Unit)? = null
) {
    Surface(
        color = backgroundColor,
        contentColor = contentColor,
        contentPadding = PaddingValues(padding),
        elevation = elevation,
        radius = Radii.Round,
        modifier = modifier
            .thenIf(onClick != null) {
                clickable { onClick?.invoke() }
            }
    ) {
        icon?.let {
            Icon(
                icon = icon,
                size = iconSize
            )
        } ?: Box(modifier = Modifier.size(iconSize.dp))
    }
}

@Preview
@Composable
private fun CircularIconPreview() {
    PreviewContent(backgroundColor = null) {
        com.dangerfield.cards.libraries.ui.components.icon.CircleIcon(
            icon = Icons.Check("Test"),
            iconSize = IconSize.Large,
            padding = Dimension.D400,
            backgroundColor = AppTheme.colors.background,
            contentColor = AppTheme.colors.content
        )
    }
}
