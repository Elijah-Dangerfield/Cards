package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AvatarCircle(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    typography: TypographyResource = AppTheme.typography.Body.B600,
    // Emoji typography scales with [size] so the emoji-to-circle ratio
    // stays consistent across surfaces (44 dp seat avatar, 100 dp profile
    // header, 112 dp edit-profile hero). See [avatarEmojiTypographyFor].
    // Override only for special-case rendering.
    emojiTypography: TypographyResource = avatarEmojiTypographyFor(size),
    emoji: String? = null,
    /**
     * Server-driven user choice. `#rrggbb` (palette-validated server-side
     * via `AvatarPalette`). Every fresh profile now ships with a real
     * value, so null/unparseable hits the surfaceSecondary fallback rather
     * than the old name-seeded hue. Single source of truth — every avatar
     * surface routes through [resolveAvatarBackground].
     */
    backgroundColorHex: String? = null,
) {
    val bg = resolveAvatarBackground(backgroundColorHex)
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji ?: initial,
            typography = if (emoji != null) emojiTypography else typography,
            color = AppTheme.colors.text,
        )
    }
}

/**
 * Resolve a server-supplied `#rrggbb` hex into the avatar's background
 * color, or fall back to the DS `surfaceSecondary` token for null /
 * unparseable values.
 *
 * Single source of truth — the welcome dialog's emoji bubble, the
 * profile screen's avatar, the edit-profile hero, and the picker swatch
 * preview all route through this so the same input always renders the
 * same color. The legacy name-seeded hue fallback is gone — every fresh
 * profile gets a real palette value at create time
 * ([com.dangerfield.cards.server.data.PostgresProfileRepository]).
 */
@Composable
fun resolveAvatarBackground(hex: String?): Color {
    val parsed = hex?.let { Catching { parseHexColor(it) }.getOrNull() }
    return parsed ?: AppTheme.colors.surfaceSecondary.color
}

/**
 * Pick an emoji typography sized for an [AvatarCircle] of [size]. Targets
 * roughly a 0.6–0.75 emoji-to-circle ratio across the scale — emojis feel
 * visually denser than letters at the same point size, so we lean toward
 * the upper end of that band rather than literal half-the-circle.
 *
 * Exported so screens that render their own avatar surfaces (the edit-
 * profile hero preview, the picker grid) can call into the same scale
 * instead of hand-picking a typography token per surface — without that,
 * the emoji ratio drifts surface to surface (which is what we just fixed).
 */
@Composable
fun avatarEmojiTypographyFor(size: Dp): TypographyResource = when {
    size <= 28.dp -> AppTheme.typography.Body.B600
    size <= 40.dp -> AppTheme.typography.Heading.H800
    size <= 52.dp -> AppTheme.typography.Heading.H900
    size <= 72.dp -> AppTheme.typography.Heading.H1100
    size <= 96.dp -> AppTheme.typography.Display.D1300
    else -> AppTheme.typography.Display.D1400
}

/**
 * Parse a `#rrggbb` hex string into a [Color]. Throws on malformed input
 * so callers wrapping in `Catching { }` can fall back to a default; we want
 * loud failures in dev and graceful fallbacks in release.
 *
 * Lives next to AvatarCircle because that's the only consumer that
 * matters today, but exported (not `internal`) so feature screens can
 * reuse the same parser for live previews of the swatch they're picking.
 */
fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    require(cleaned.length == 6) { "Expected #rrggbb, got: $hex" }
    val r = cleaned.substring(0, 2).toInt(16)
    val g = cleaned.substring(2, 4).toInt(16)
    val b = cleaned.substring(4, 6).toInt(16)
    return Color(red = r, green = g, blue = b)
}

// ---------------------------------------------------------------------------
// Previews — one per axis of variation the component handles. Together they
// pin the emoji-to-circle ratio at each size band, the initial fallback when
// no emoji is set, and the palette-vs-default background path.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun AvatarCirclePreview_ScaleRow_Emoji() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            AvatarCircle(name = "E", emoji = "🦊", backgroundColorHex = "#E48A58", size = 28.dp)
            AvatarCircle(name = "E", emoji = "🦊", backgroundColorHex = "#E48A58", size = 44.dp)
            AvatarCircle(name = "E", emoji = "🦊", backgroundColorHex = "#E48A58", size = 72.dp)
            AvatarCircle(name = "E", emoji = "🦊", backgroundColorHex = "#E48A58", size = 100.dp)
        }
    }
}

@Preview
@Composable
private fun AvatarCirclePreview_InitialFallback() {
    // Emoji null → first letter of name renders in the configured
    // typography (not the emoji scale). Pins the "user hasn't picked
    // an avatar yet" branch.
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            AvatarCircle(name = "Elijah", emoji = null, backgroundColorHex = "#5894E4")
            AvatarCircle(name = "kat", emoji = null, backgroundColorHex = "#A8E458", size = 72.dp)
            AvatarCircle(name = "", emoji = null, backgroundColorHex = null, size = 72.dp)
        }
    }
}

@Preview
@Composable
private fun AvatarCirclePreview_BackgroundResolution() {
    // Left → valid palette hex → renders that color.
    // Middle → null → resolveAvatarBackground falls back to
    // surfaceSecondary token.
    // Right → malformed string → parser throws, Catching swallows,
    // same surfaceSecondary fallback. Pins the resolver's contract.
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarCircle(name = "E", emoji = "🦄", backgroundColorHex = "#C658E4", size = 72.dp)
                AvatarCircle(name = "E", emoji = "🦄", backgroundColorHex = null, size = 72.dp)
                AvatarCircle(name = "E", emoji = "🦄", backgroundColorHex = "not-a-hex", size = 72.dp)
            }
        }
    }
}
