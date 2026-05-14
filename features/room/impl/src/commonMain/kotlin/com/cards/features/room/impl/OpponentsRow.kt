package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.LastActionPill
import com.dangerfield.cards.libraries.ui.components.poker.PulsingActiveRing
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

@Composable
internal fun OpponentsRow(table: TableUiState.Active) {
    val opponents = table.seats.filter { !it.isHuman }
    val winners = table.handResult?.winners?.map { it.seatIndex }?.toSet().orEmpty()
    // Each opponent gets equal share of the row via weight, and the avatar
    // size scales with that share: at 2 opponents the avatars are big and
    // beefy, at 6 they're small and compact, but it never wraps or clips.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val count = opponents.size.coerceAtLeast(1)
        val perOpponent = (rowWidth / count).coerceAtLeast(56.dp)
        // Leave ~28% margin so name/stack/chip pill have breathing room.
        val avatarSize = (perOpponent * 0.62f).coerceIn(40.dp, 76.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            opponents.forEach { seat ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    OpponentSeat(
                        seat = seat,
                        isWinner = seat.index in winners,
                        avatarSize = avatarSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpponentSeat(seat: SeatView, isWinner: Boolean, avatarSize: Dp) {
    val folded = seat.participation == HandParticipation.Folded
    val ringSize = avatarSize + 12.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .alpha(if (folded) 0.4f else 1f),
    ) {
        // Chevron and action pill both float above the avatar via TopCenter
        // overlays — no reserved vertical strip — so the row sits flush at the
        // top of its column whether anyone has acted yet or not. Chevron + pill
        // never co-exist (the engine clears pills on StreetAdvanced before the
        // seat becomes active again), so positioning both at the top is safe.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
            if (seat.isActing) PulsingActiveRing(modifier = Modifier.size(ringSize))
            if (isWinner) WinnerGlow(modifier = Modifier.size(ringSize))
            AvatarCircle(name = seat.displayName, size = avatarSize, emoji = seat.emoji)

            // Active-turn chevron — floats just above the avatar's outer ring.
            ChevronOverlay(
                visible = seat.isActing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-16).dp),
            )

            // Last-action pill — slides in from above and overlaps the avatar's
            // top edge by a few dp so the chip feels like it lands on the seat
            // instead of floating in dead space.
            LastActionOverlay(
                action = seat.lastAction,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-6).dp),
            )

            // Dealer / SB / BB chip — overlapping inward over the avatar's
            // bottom-right so the edge seats in the row don't clip the badge
            // against the screen margin.
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatCompactChips(seat.stack),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (seat.contributedThisStreet > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            ChipPill(amount = seat.contributedThisStreet)
        }
    }
}

/**
 * Extracted so the `AnimatedVisibility` call site sees only `BoxScope` and
 * resolves to the top-level overload. Inlining this into `OpponentSeat` puts
 * it inside an outer `Column { Box { ... } }`, which makes the `ColumnScope`
 * extension of `AnimatedVisibility` ambiguous with the top-level function.
 */
@Composable
private fun ChevronOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(140)),
        ) {
            Text(
                text = "▼",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

@Composable
private fun LastActionOverlay(action: PlayerAction?, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = action != null,
            enter = slideInVertically(animationSpec = tween(240)) { -it } +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(animationSpec = tween(180)) { -it } +
                fadeOut(animationSpec = tween(140)),
        ) {
            action?.let { LastActionPill(label = it.shortLabel()) }
        }
    }
}
