package com.dangerfield.cards.libraries.ui.components.icon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.avatarEmojiTypographyFor
import com.dangerfield.cards.libraries.ui.components.icon.IconButton.Size
import com.dangerfield.cards.libraries.ui.components.text.CenteredGlyph
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import androidx.compose.ui.tooling.preview.Preview

/**
 * Emoji-glyph counterpart to [IconButton]. Shares the same [Size] scale,
 * Surface shell, and radius so a cluster of icon buttons + emoji buttons
 * reads as one set of controls. The KMP material-icons bundle this
 * project pulls doesn't include face / emoji vectors, so emoji controls
 * render a literal glyph via [CenteredGlyph], which trims the line box so
 * the glyph sits optically centered (a plain centered Text floats it high).
 *
 * The button's footprint matches IconButton's (iconSize + padding on each
 * side), and the emoji is sized to that full circle via the same
 * [avatarEmojiTypographyFor] ratio the avatars use. Sizing the glyph to the
 * inner iconSize box instead made the emoji — which renders ~1.25× its font
 * size — fill and overflow that box, so the emote button read oversized and
 * clipped next to its icon-button siblings.
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
    val diameter = size.iconSize.dp + size.padding * 2
    Surface(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        color = backgroundColor,
        contentColor = contentColor,
        radius = Radii.IconButton,
        onClick = onClick,
        enabled = enabled,
        role = Role.Button,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.Center,
        ) {
            CenteredGlyph(
                text = emoji,
                typography = avatarEmojiTypographyFor(diameter),
                color = contentColor.color,
            )
        }
    }
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
                EmojiButton(emoji = "🙁", size = size, onClick = {})
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
