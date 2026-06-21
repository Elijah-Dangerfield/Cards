package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * A short horizontal run of overlapping mini-avatars — the "who's here so far"
 * affordance used on matchmaking + lobby summaries. Renders [seats] as
 * overlapping [AvatarCircle]s followed by [empty] dashed "+" placeholders for
 * the seats still being held open.
 *
 * Each avatar gets a thin ring in the surrounding [ringColor] so the overlap
 * reads cleanly against a busy background.
 */
data class SeatAvatar(
    val name: String,
    val emoji: String?,
    val backgroundColorHex: String?,
)

@Composable
fun SeatStack(
    seats: List<SeatAvatar>,
    modifier: Modifier = Modifier,
    empty: Int = 0,
    size: Dp = 28.dp,
    overlap: Dp = 9.dp,
) {
    val ring = AppTheme.colors.surface.color
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        seats.forEachIndexed { index, seat ->
            Box(
                modifier = Modifier
                    .offset(x = if (index == 0) 0.dp else -overlap * index)
                    .size(size)
                    .clip(CircleShape)
                    .border(width = 2.dp, color = ring, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AvatarCircle(
                    name = seat.name,
                    emoji = seat.emoji,
                    backgroundColorHex = seat.backgroundColorHex,
                    size = size,
                    animationsEnabled = false,
                )
            }
        }
        repeat(empty) { i ->
            val index = seats.size + i
            Box(
                modifier = Modifier
                    .offset(x = if (index == 0) 0.dp else -overlap * index)
                    .size(size)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceRaised.color)
                    .border(width = 2.dp, color = ring, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentDisabled,
                )
            }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun SeatStackPreview() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        SeatStack(
            seats = listOf(
                SeatAvatar("Marisol", "🦁", "#C658E4"),
                SeatAvatar("Theo", "🐯", "#E48A58"),
                SeatAvatar("You", "🦊", "#5894E4"),
            ),
            empty = 3,
            size = 36.dp,
        )
    }
}
