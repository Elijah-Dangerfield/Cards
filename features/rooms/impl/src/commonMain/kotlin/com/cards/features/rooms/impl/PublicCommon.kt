package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii

/** The blue "public" hero gradient (SPEC §0 — `info` family). */
@Composable
internal fun infoGradient(): Brush {
    val info = AppTheme.colors.info.color
    return Brush.linearGradient(listOf(info, lerp(info, Color.Black, 0.22f)))
}

/** A gradient hero card — leading glyph/badge, title, body. Fronts the Find explainer. */
@Composable
internal fun PublicHeroCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.R850.shape)
            .background(infoGradient())
            .padding(Dimension.D750),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            Spacer(Modifier.size(Dimension.D100))
            Text(
                text = body,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content.withAlpha(0.80f),
            )
        }
    }
}

/** A circular translucent badge on the hero gradient — holds a glyph/timer. */
@Composable
internal fun HeroBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        typography = AppTheme.typography.Label.L300,
        color = AppTheme.colors.contentTertiary,
        allCaps = true,
        modifier = modifier,
    )
}
