package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Small inline status pill — short text label on a rounded, color-filled
 * background. The shape that surfaces like "Lvl 14" under a seat,
 * "1 unread" on a profile chip, or "🏛 Founding member" all share.
 *
 * Where [LeadingPill] is the *header* shape (D400 padding, surfaceSecondary
 * default, designed to hold an 18dp leading icon + a trailing label and
 * sit alongside ChipBadge / LevelPill), [StatusPill] is the *inline
 * badge* shape (D300/D100 padding by default, caller-chosen background,
 * designed for tight surfaces and short status text).
 *
 * Surface tokens stay caller-owned: pass `accentPrimary` /
 * `accentSecondary` for "look at me" status, `surfaceTertiary` for the
 * quiet info badge. The DS doesn't presume the intent.
 *
 * Slot variant accepts optional leading content (emoji / icon) by
 * composing it before the [content] block — callers wanting "🏛 Founding
 * member" pass a [Text] for the emoji then a [Text] for the label.
 */
@Composable
fun StatusPill(
    background: ColorResource,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = StatusPillDefaults.padding,
    horizontalSpacing: Dp = Dimension.D100,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(Radii.Round.shape)
            .background(background.color)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
    ) {
        content()
    }
}

/**
 * Static-text shorthand for the common case — text-only pill with a
 * matched foreground color. Falls through to the slot variant.
 */
@Composable
fun StatusPill(
    text: String,
    background: ColorResource,
    foreground: ColorResource,
    modifier: Modifier = Modifier,
    typography: TypographyResource = AppTheme.typography.Caption.C200.SemiBold,
    contentPadding: PaddingValues = StatusPillDefaults.padding,
) {
    StatusPill(
        background = background,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            typography = typography,
            color = foreground,
        )
    }
}

object StatusPillDefaults {
    /**
     * Default padding sized for the "1 unread" / "Founding member" /
     * "Lvl 14" family — comfortable around Caption.C200 / Label.L300
     * without inflating row height. Callers with a tighter footprint
     * (under-avatar pills, multi-pill grids) pass a smaller override.
     */
    val padding: PaddingValues = PaddingValues(
        horizontal = Dimension.D300,
        vertical = Dimension.D100,
    )
}

@Preview
@Composable
private fun StatusPillPreview_AccentPrimary() {
    PreviewContent {
        StatusPill(
            text = "1 unread",
            background = AppTheme.colors.accentPrimary,
            foreground = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Preview
@Composable
private fun StatusPillPreview_AccentSecondaryWithLeadingEmoji() {
    PreviewContent {
        StatusPill(
            background = AppTheme.colors.accentSecondary,
        ) {
            Text(
                text = "🏛",
                typography = AppTheme.typography.Caption.C200,
            )
            Text(
                text = "Founding member",
                typography = AppTheme.typography.Caption.C200.SemiBold,
                color = AppTheme.colors.onAccentSecondary,
            )
        }
    }
}

@Preview
@Composable
private fun StatusPillPreview_SurfaceTertiary() {
    PreviewContent {
        StatusPill(
            text = "Lvl 14",
            background = AppTheme.colors.surfaceHigh,
            foreground = AppTheme.colors.contentSecondary,
            typography = AppTheme.typography.Label.L300,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
