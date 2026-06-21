package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.room.RoomSeatPlayer
import com.dangerfield.cards.libraries.ui.components.room.RoomSeatStatus
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

/**
 * A gradient hero card — leading glyph/badge, title, body. Shared by the Find
 * explainer, the Lobby auto-deal card, and the NextRound card.
 */
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

/** Read-only stakes/buy-in tile (public stakes are never editable — lock glyph). */
@Composable
internal fun ReadOnlyStakeCard(
    label: String,
    value: String,
    showCoin: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCoin) {
            ChipCoin(size = 18.dp)
            Spacer(Modifier.size(Dimension.D400))
        }
        Column(modifier = Modifier.weight(1f)) {
            Eyebrow(label)
            Text(value, typography = AppTheme.typography.Label.L500, color = AppTheme.colors.content)
        }
        // Lock — public stakes are fixed.
        Text("🔒", typography = AppTheme.typography.Caption.C300, color = AppTheme.colors.contentTertiary)
    }
}

/** Static placeholder occupants for the public Lobby shell. */
internal fun lobbySeats(): List<RoomSeatPlayer?> = listOf(
    RoomSeatPlayer(name = "Marisol", emoji = "🦁", avatarBackgroundColorHex = "#C658E4"),
    RoomSeatPlayer(name = "Theo", emoji = "🐯", avatarBackgroundColorHex = "#E48A58"),
    RoomSeatPlayer(name = "You", emoji = "🦊", avatarBackgroundColorHex = "#5894E4", isYou = true),
    RoomSeatPlayer(name = "Priya", emoji = "🐼", avatarBackgroundColorHex = "#A8E458", status = RoomSeatStatus.Joining),
    null,
    null,
)

/** Static placeholder occupants for the public NextRound shell (you = up next). */
internal fun nextRoundSeats(): List<RoomSeatPlayer?> = listOf(
    RoomSeatPlayer(name = "Marisol", emoji = "🦁", avatarBackgroundColorHex = "#C658E4"),
    RoomSeatPlayer(name = "Theo", emoji = "🐯", avatarBackgroundColorHex = "#E48A58"),
    RoomSeatPlayer(name = "Priya", emoji = "🐼", avatarBackgroundColorHex = "#A8E458"),
    RoomSeatPlayer(name = "You", emoji = "🦊", avatarBackgroundColorHex = "#5894E4", isYou = true, status = RoomSeatStatus.UpNext),
    RoomSeatPlayer(name = "Bao", emoji = "🐲", avatarBackgroundColorHex = "#5DA75A"),
    null,
)
