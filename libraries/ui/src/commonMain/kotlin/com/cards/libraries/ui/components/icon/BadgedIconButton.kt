package com.dangerfield.cards.libraries.ui.components.icon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Badge
import com.dangerfield.cards.libraries.ui.components.BadgedBox
import com.dangerfield.cards.libraries.ui.components.icon.IconButton.Size
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * An [IconButton] that carries a notification badge in its top-right corner.
 *
 * Mirrors the bottom-bar tab affordance ([BottomBarBadge] / [BottomBarBadgeDot]):
 * a non-zero [badgeCount] shows a numbered pill, otherwise a bare [showDot]
 * raises the dot. Both default off so the button reads exactly like a plain
 * [IconButton] when there's nothing to surface. Tint is the accent token so the
 * badge language stays unified with the rest of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgedIconButton(
    icon: IconResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    showDot: Boolean = false,
    backgroundColor: ColorResource? = AppTheme.colors.surface,
    iconColor: ColorResource = LocalContentColor.current,
    size: Size = Size.Medium,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    BadgedBox(
        modifier = modifier,
        badgeTranslation = DpOffset(x = (-4).dp, y = 4.dp),
        badge = {
            when {
                badgeCount > 0 -> Badge(
                    containerColor = AppTheme.colors.accentPrimary.color,
                    contentColor = AppTheme.colors.content.color,
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = Dimension.D200,
                            vertical = Dimension.D25,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                            typography = AppTheme.typography.Caption.C200.SemiBold,
                        )
                    }
                }

                showDot -> Badge(containerColor = AppTheme.colors.accentPrimary.color)
            }
        },
    ) {
        IconButton(
            icon = icon,
            onClick = onClick,
            backgroundColor = backgroundColor,
            iconColor = iconColor,
            size = size,
            enabled = enabled,
            interactionSource = interactionSource,
        )
    }
}

@Preview
@Composable
private fun BadgedIconButtonPreview_Count() {
    PreviewContent {
        BadgedIconButton(
            icon = Icons.Settings(""),
            onClick = {},
            badgeCount = 3,
        )
    }
}

@Preview
@Composable
private fun BadgedIconButtonPreview_Dot() {
    PreviewContent {
        BadgedIconButton(
            icon = Icons.Settings(""),
            onClick = {},
            showDot = true,
        )
    }
}

@Preview
@Composable
private fun BadgedIconButtonPreview_None() {
    PreviewContent {
        BadgedIconButton(
            icon = Icons.Settings(""),
            onClick = {},
        )
    }
}
