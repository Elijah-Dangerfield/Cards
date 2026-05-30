package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.system.color.ColorResource

@Composable
fun Card(
    modifier: Modifier = Modifier,
    color: ColorResource? = AppTheme.colors.surface,
    contentColor: ColorResource = AppTheme.colors.content,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        onClick = onClick ?: {},
        bounceScale = if (onClick != null) 0.9f else 1f,
        elevation = Elevation.Button,
        radius = Radii.Card,
        contentPadding = PaddingValues(Dimension.D1000)
    ) {
        content()
    }
}


@Composable
fun CardSecondary(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = AppTheme.colors.surfaceRaised,
        contentColor = AppTheme.colors.contentSecondary,
        onClick = onClick ?: {},
        bounceScale = if (onClick != null) 0.9f else 1f,
        elevation = Elevation.Button,
        radius = Radii.Card,
        contentPadding = PaddingValues(Dimension.D1000)
    ) {
        content()
    }
}
