package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.LineHeightStyle
import com.dangerfield.cards.system.typography.TypographyResource

/**
 * Renders a single glyph (emoji or letter) on its optical midpoint inside the
 * caller's centering box. A plain centered Text floats the glyph slightly high:
 * the line box reserves ascent/descent space the glyph doesn't fill, so the
 * visual center sits above the geometric center. Trimming the half-leading
 * (Trim.Both) and centering within the line box (Alignment.Center) pins the
 * glyph dead-center.
 *
 * Shared by AvatarCircle (seat + profile avatars) and EmojiButton (play-screen
 * emote trigger) — both render a glyph centered in a circle and both want this
 * fix. Drops to [BasicText] because the DS Text doesn't expose lineHeightStyle.
 */
@Composable
internal fun CenteredGlyph(
    text: String,
    typography: TypographyResource,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = typography.style.copy(
            color = color,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
    )
}
