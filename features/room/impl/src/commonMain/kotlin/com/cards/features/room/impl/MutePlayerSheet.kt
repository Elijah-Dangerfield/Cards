package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800

/**
 * Tap-an-opponent surface: shows a small avatar header plus a single
 * action — "Mute this player's emoji" — that toggles persistent mute via
 * [PlayPokerAction.ToggleMutePlayer].
 *
 * In V1 single-player vs bots, no one but the human emits emoji, so the
 * mute set never actually filters anything visible today. The sheet is
 * still wired and persisted so that when MP/reactive-bot blasts ship in
 * V1.x (product-spec.md §5.5), the user's existing mute preferences are
 * already in place — they don't get a wall of unfamiliar emoji at first
 * exposure.
 */
@Composable
internal fun MutePlayerSheet(
    seat: SeatView,
    isMuted: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.surfacePrimary,
        showCloseButton = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarCircle(
                name = seat.displayName,
                size = 72.dp,
                emoji = seat.emoji,
                backgroundColorHex = seat.avatarBackgroundColorHex,
            )
            VerticalSpacerD500()
            Text(
                text = seat.displayName,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD800()
            MuteToggleRow(
                isMuted = isMuted,
                onClick = {
                    onToggle()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun MuteToggleRow(isMuted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimension.D800))
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D800, vertical = Dimension.D700),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        Box(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = if (isMuted) "Unmute emoji" else "Mute emoji",
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Text(
                text = if (isMuted) {
                    "Their blasts will show on your screen again."
                } else {
                    "You won't see their table blasts on your screen."
                },
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
                modifier = Modifier.padding(top = Dimension.D200),
            )
        }
    }
}
