package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Vertical accent wash — kept for [TutorialBanner] / [PrivateChooseSheet], which
 * still render a gradient panel. The Play cards themselves are flat now.
 */
internal fun playGradient(accent: Color): Brush = Brush.verticalGradient(
    colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.68f)),
)

/**
 * The PUBLIC rooms hero — a flat surface card with an emoji tile, a short pitch,
 * and a full-width gold "Find a table" CTA. Clean and bubbly: no gradient, no
 * hand-drawn glyphs (Apple over Microsoft).
 */
@Composable
internal fun PublicRoomsCard(
    title: String,
    subtitle: String,
    cta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
            .clickable(onClick = onClick)
            .padding(Dimension.D700),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            EmojiTile(emoji = "🌐", background = AppTheme.colors.surfaceRaised.color, shape = Radii.R600.shape)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(Dimension.D600))
        Button(
            onClick = onClick,
            accent = ButtonAccent.Primary,
            size = ButtonSize.Large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(cta)
        }
    }
}

/**
 * A flat play-option row — an emoji circle in the option's accent colour, a title
 * + subtitle, and a trailing chevron (or a [tag] pill for a not-yet-shipped
 * option). Used for Private room, Practice, Tournament.
 */
@Composable
internal fun PlayRow(
    title: String,
    subtitle: String,
    emoji: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            // A disabled option (e.g. a not-yet-shipped mode) reads as greyed out
            // and ignores taps, so it advertises "coming" without leading anywhere.
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimension.D700, vertical = Dimension.D700),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        EmojiTile(emoji = emoji, background = accent, shape = CircleShape)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
        if (tag != null) {
            StatusPill(
                text = tag,
                background = AppTheme.colors.surfaceHigh,
                foreground = AppTheme.colors.content,
                // One step up from the pill default so "Soon" reads a touch bolder.
                typography = AppTheme.typography.Caption.C300.SemiBold,
            )
        } else {
            Icon(
                icon = Icons.ChevronRight(""),
                size = IconSize.Small,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

@Composable
private fun EmojiTile(emoji: String, background: Color, shape: Shape, size: Dp = 48.dp) {
    Box(
        modifier = Modifier.size(size).clip(shape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, typography = AppTheme.typography.Heading.H900)
    }
}

@Preview
@Composable
private fun PlayCardsPreview() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PublicRoomsCard(
                title = "Public rooms",
                subtitle = "Play against anyone, seated instantly",
                cta = "Find a table",
                onClick = {},
            )
            PlayRow(
                title = "Private room",
                subtitle = "Join by code",
                emoji = "🔒",
                accent = AppTheme.colors.accentSecondary.color,
                onClick = {},
            )
            PlayRow(
                title = "Practice",
                subtitle = "vs bots, no chips at stake",
                emoji = "🤖",
                accent = AppTheme.colors.league.amethyst.color,
                onClick = {},
            )
            PlayRow(
                title = "Tournament",
                subtitle = "Royal Flush",
                emoji = "🏆",
                accent = AppTheme.colors.accentTertiary.color,
                onClick = {},
                tag = "Soon",
                enabled = false,
            )
        }
    }
}
