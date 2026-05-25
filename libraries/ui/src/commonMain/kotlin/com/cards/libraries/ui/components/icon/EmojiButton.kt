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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.icon.IconButton.Size
import com.dangerfield.cards.libraries.ui.components.text.Text
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
 * render a literal glyph via [Text].
 *
 * Sizing note: EmojiButton's footprint is a few dp taller than the
 * matching [IconButton] at the same [Size] because emoji typography
 * uses a 1.25× line-height ratio (per LineHeightRatio.MODERATE) and
 * the glyph needs the full line box to sit centered on the baseline.
 * We use [defaultMinSize] to lock a minimum square footprint matching
 * IconButton's iconSize, then let the line box grow the bounds if it
 * needs more vertical room. Trying to clamp the inner box to exactly
 * iconSize.dp pushes emoji glyphs out the bottom of the layout (the
 * line-box midpoint sits above the glyph's visual midpoint for emoji,
 * even with PlatformTextStyle / LineHeightStyle tweaks), so we accept
 * the small footprint difference in exchange for proper centering.
 */
@NonRestartableComposable
@Composable
fun EmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: ColorResource? = AppTheme.colors.surfacePrimary,
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
            Text(
                text = emoji,
                typography = size.emojiTypography,
                color = contentColor,
                textAlign = TextAlign.Center,
                softWrap = false,
                overflow = TextOverflow.Visible,
                maxLines = 1,
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
