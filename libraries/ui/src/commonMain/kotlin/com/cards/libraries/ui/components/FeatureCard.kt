package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii

/**
 * A full-width gradient card with leading glyph block, title + subtitle, and trailing chevron.
 * Used for primary calls-to-action on tab screens: bot difficulties, multiplayer entry, etc.
 *
 * The glyph slot is a 44dp rounded square. Pass an emoji, a card-suit symbol, or any short glyph.
 */
@Composable
fun FeatureCard(
    title: String,
    subtitle: String,
    accent: Color,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.7f)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(Radii.R700.shape)
                .background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = AppTheme.colors.text.color,
        )
    }
}

object FeatureCardAccents {
    val Green = Color(0xFF2D5F4A)
    val Blue = Color(0xFF2D4A6F)
    val Magenta = Color(0xFF6F2D4A)
    val Gold = Color(0xFF6F5F2D)
}
