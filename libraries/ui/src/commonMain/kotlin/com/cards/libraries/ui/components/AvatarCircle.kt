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
) {
    val seed = name.hashCode()
    val bg = avatarHues[((seed % avatarHues.size) + avatarHues.size) % avatarHues.size]
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
