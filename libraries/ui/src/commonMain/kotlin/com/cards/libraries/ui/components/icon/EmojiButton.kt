package com.dangerfield.cards.libraries.ui.components.icon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.icon.IconButton.Size
import com.dangerfield.cards.libraries.ui.components.text.CenteredGlyph
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Emoji-glyph counterpart to [IconButton]. Shares the same [Size] scale,
 * Surface shell, and radius so a cluster of icon buttons + emoji buttons
 * reads as one set of controls. The KMP material-icons bundle this
 * project pulls doesn't include face / emoji vectors, so emoji controls
 * render a literal glyph via [CenteredGlyph], which trims the line box so
 * the glyph sits optically centered (a plain centered Text floats it high).
 * A [defaultMinSize] square keeps the footprint matching IconButton's iconSize.
 */
@NonRestartableComposable
@Composable
fun EmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: ColorResource? = AppTheme.colors.surface,
    contentColor: ColorResource = LocalContentColor.current,
    size: Size = Size.Medium,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Surface(
        modifier = modifier,
        contentPadding = PaddingValues(size.padding),
        color = backgroundColor,
        contentColor = contentColor,
        radius = Radii.IconButton,
        onClick = onClick,
        enabled = enabled,
        role = Role.Button,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier.defaultMinSize(
                minWidth = size.iconSize.dp,
                minHeight = size.iconSize.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            CenteredGlyph(
                text = emoji,
                typography = size.emojiTypography,
                color = contentColor.color,
            )
        }
    }
}

private val Size.emojiTypography: TypographyResource
    @Composable
    get() = when (this) {
        Size.Smallest -> AppTheme.typography.Heading.H700
        Size.Small -> AppTheme.typography.Heading.H800
        Size.Medium -> AppTheme.typography.Heading.H900
        Size.Large -> AppTheme.typography.Heading.H1000
        Size.Largest -> AppTheme.typography.Heading.H1100
    }

@Preview
@Composable
private fun EmojiButtonPreview_Sizes() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Size.entries.forEach { size ->
                EmojiButton(emoji = "🔥", size = size, onClick = {})
            }
        }
    }
}

@Preview
@Composable
private fun EmojiButtonPreview_Transparent() {
    PreviewContent {
        EmojiButton(
            emoji = "🎉",
            size = Size.Large,
            backgroundColor = null,
            onClick = {},
        )
    }
}
