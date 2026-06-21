package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.dashedBorder
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Presence/role state for a seated player in a room grid. */
enum class RoomSeatStatus { Seated, Joining, UpNext }

/**
 * The data a single occupied [RoomSeat] renders. Purely presentational — map
 * a `RoomMember` / table occupant into this at the call site.
 */
data class RoomSeatPlayer(
    val name: String,
    val emoji: String? = null,
    val avatarBackgroundColorHex: String? = null,
    val isHost: Boolean = false,
    val isBot: Boolean = false,
    val isYou: Boolean = false,
    val status: RoomSeatStatus = RoomSeatStatus.Seated,
)

/**
 * One tile in a room's 3-column seat grid. Three shapes:
 *  - **Filled** ([player] != null): avatar + name, optional HOST (gold) / BOT
 *    (teal) badge, a ring when it's you or you're up-next, dimmed while
 *    "joining…".
 *  - **Open** ([player] == null, [addBot] == false): neutral dashed "+" with
 *    an "Open" label — a seat anyone can still take.
 *  - **Add a bot** ([player] == null, [addBot] == true): teal-tinted dashed
 *    affordance the host taps to seat a bot. Pass `addBot = true` only in the
 *    private host lobby.
 *
 * Designed to drop into `NonLazyVerticalGrid(columns = 3, …)`; the tile fills
 * its column width at a fixed 1 : 1.1 aspect ratio.
 */
@Composable
fun RoomSeat(
    player: RoomSeatPlayer?,
    modifier: Modifier = Modifier,
    addBot: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .fillMaxWidth()
        .aspectRatio(1f / 1.1f)

    when {
        player == null && addBot -> AddBotSeat(base, onClick)
        player == null -> OpenSeat(base)
        else -> FilledSeat(base, player, onClick)
    }
}

@Composable
private fun FilledSeat(
    modifier: Modifier,
    player: RoomSeatPlayer,
    onClick: (() -> Unit)?,
) {
    val upNext = player.status == RoomSeatStatus.UpNext
    val joining = player.status == RoomSeatStatus.Joining
    val ring: ColorResource? = when {
        upNext -> AppTheme.colors.accentPrimary
        player.isYou -> AppTheme.colors.accentSecondary
        else -> null
    }
    val container = if (upNext) AppTheme.colors.accentPrimarySubtle.color else AppTheme.colors.surface.color
    Column(
        modifier = modifier
            .clip(Radii.R750.shape)
            .background(container)
            .then(
                if (upNext) {
                    Modifier.border(1.dp, AppTheme.colors.accentPrimary.withAlpha(0.4f).color, Radii.R750.shape)
                } else {
                    Modifier.border(1.dp, AppTheme.colors.border.color, Radii.R750.shape)
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (joining) 0.6f else 1f)
            .padding(Dimension.D400),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = if (ring != null) {
                    Modifier
                        .size(50.dp)
                        .border(2.dp, ring.color, CircleShape)
                        .padding(2.dp)
                } else {
                    Modifier
                },
                contentAlignment = Alignment.Center,
            ) {
                AvatarCircle(
                    name = player.name,
                    emoji = player.emoji,
                    backgroundColorHex = player.avatarBackgroundColorHex,
                    size = 46.dp,
                    animationsEnabled = false,
                )
            }
            if (player.isHost) {
                RoleBadge("HOST", AppTheme.colors.accentPrimary, AppTheme.colors.onAccentPrimary)
            } else if (player.isBot) {
                RoleBadge("BOT", AppTheme.colors.accentSecondary, AppTheme.colors.onAccentSecondary)
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(Dimension.D300))
        Text(
            text = player.name,
            typography = AppTheme.typography.Label.L400,
            color = if (player.isYou) AppTheme.colors.accentSecondary else AppTheme.colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        when {
            joining -> Text(
                text = "joining…",
                typography = AppTheme.typography.Caption.C300,
                color = AppTheme.colors.contentTertiary,
            )
            upNext -> Text(
                text = "up next",
                typography = AppTheme.typography.Caption.C300,
                color = AppTheme.colors.accentPrimary,
            )
        }
    }
}

@Composable
private fun RoleBadge(label: String, background: ColorResource, foreground: ColorResource) {
    Box(
        modifier = Modifier
            .offset(y = 6.dp)
            .clip(CircleShape)
            .background(background.color)
            .padding(horizontal = Dimension.D300, vertical = 1.dp),
    ) {
        Text(text = label, typography = AppTheme.typography.Caption.C200, color = foreground)
    }
}

@Composable
private fun OpenSeat(modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(Radii.R750.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.025f))
            .dashedBorder(
                strokeWidth = 1.5.dp,
                color = AppTheme.colors.surfaceHigh,
                radius = Radii.R750,
            )
            .padding(Dimension.D400),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .dashedBorder(strokeWidth = 1.5.dp, color = AppTheme.colors.surfaceHigh, radius = Radii.Round),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+", typography = AppTheme.typography.Heading.H700, color = AppTheme.colors.contentDisabled)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(Dimension.D300))
        Text(text = "Open", typography = AppTheme.typography.Label.L400, color = AppTheme.colors.contentDisabled)
    }
}

@Composable
private fun AddBotSeat(modifier: Modifier, onClick: (() -> Unit)?) {
    val teal = AppTheme.colors.accentSecondary
    Column(
        modifier = modifier
            .clip(Radii.R750.shape)
            .background(teal.withAlpha(0.06f).color)
            .dashedBorder(strokeWidth = 1.5.dp, color = teal.withAlpha(0.47f), radius = Radii.R750)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Dimension.D400),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(teal.withAlpha(0.12f).color)
                .border(1.5.dp, teal.withAlpha(0.33f).color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BotGlyph(teal)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(Dimension.D300))
        Text(text = "Add a bot", typography = AppTheme.typography.Label.L400, color = teal)
    }
}

/** A rounded-head bot glyph (head, antenna dot, two eyes) — drawn so it needs no icon asset. */
@Composable
internal fun BotGlyph(color: ColorResource, sizeDp: androidx.compose.ui.unit.Dp = 24.dp) {
    val c = color.color
    Canvas(modifier = Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.07f)
        // Head
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.21f, h * 0.33f),
            size = Size(w * 0.58f, h * 0.42f),
            cornerRadius = CornerRadius(w * 0.13f, w * 0.13f),
            style = stroke,
        )
        // Antenna
        drawLine(c, Offset(w * 0.5f, h * 0.33f), Offset(w * 0.5f, h * 0.23f), strokeWidth = stroke.width)
        drawCircle(c, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.19f))
        // Eyes
        drawCircle(c, radius = w * 0.055f, center = Offset(w * 0.39f, h * 0.53f))
        drawCircle(c, radius = w * 0.055f, center = Offset(w * 0.61f, h * 0.53f))
    }
}

@Preview
@Composable
private fun RoomSeatPreview() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        com.dangerfield.cards.libraries.ui.components.NonLazyVerticalGrid(
            columns = 3,
            data = listOf(0, 1, 2, 3, 4, 5),
            horizontalSpacing = 11.dp,
            verticalSpacing = 11.dp,
        ) { index, _ ->
            when (index) {
                0 -> RoomSeat(RoomSeatPlayer("You", "🦊", "#5894E4", isHost = true, isYou = true))
                1 -> RoomSeat(RoomSeatPlayer("Jordan", "🐱", "#E48A58"))
                2 -> RoomSeat(RoomSeatPlayer("Priya", "🐼", "#A8E458", status = RoomSeatStatus.Joining))
                3 -> RoomSeat(RoomSeatPlayer("Ace Bot", "🤖", "#2E4F49", isBot = true))
                4 -> RoomSeat(player = null, addBot = true)
                else -> RoomSeat(player = null)
            }
        }
    }
}
