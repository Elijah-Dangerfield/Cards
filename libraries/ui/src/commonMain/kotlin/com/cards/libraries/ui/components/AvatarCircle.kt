package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.typography.TypographyResource

private val avatarHues: List<Color> = listOf(
    Color(0xFFE07AB1),
    Color(0xFFF6B26B),
    Color(0xFFFFD966),
    Color(0xFF93C47D),
    Color(0xFF76A5AF),
    Color(0xFF8E7CC3),
)

@Composable
fun AvatarCircle(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    typography: TypographyResource = AppTheme.typography.Body.B600,
    // Emojis are visually denser than letters at the same point size and read
    // smaller inside the circle. Default to a larger token so an avatar emoji
    // feels like the avatar, not a punctuation mark sitting in it.
    emojiTypography: TypographyResource = AppTheme.typography.Display.D1100,
    emoji: String? = null,
    /**
     * Server-driven user choice. `#rrggbb` (palette-validated server-side
     * via [com.dangerfield.cards.server.domain.AvatarPalette]). Null =
     * fall back to the legacy name-seeded hue so anonymous / un-customized
     * avatars still feel distinct. Pass `Color.Unspecified` (via the
     * String null path) to mean "use default."
     */
    backgroundColorHex: String? = null,
) {
    val parsedBg = backgroundColorHex?.let { runCatching { parseHexColor(it) }.getOrNull() }
    val bg = if (parsedBg != null) {
        parsedBg
    } else {
        val seed = name.hashCode()
        avatarHues[((seed % avatarHues.size) + avatarHues.size) % avatarHues.size]
    }
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
 * Parse a `#rrggbb` hex string into a [Color]. Throws on malformed input
 * so callers wrapping in runCatching can fall back to a default; we want
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
