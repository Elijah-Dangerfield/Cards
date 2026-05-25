package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii

/**
 * Small pill rendered under a seat's avatar on the play screen.
 *
 * Shows the local user's level ("Lvl 14") on the human seat and a bot
 * difficulty tag ("Bot · Standard") on bot seats. Empty seats and
 * remote humans whose level we don't have a source for yet pass
 * `text = null` and the composable renders nothing — keeps the call
 * sites in [OpponentsRow] and [PlayerArea] free of `?.let { }` noise.
 *
 * The pill collapses to zero size when [visible] is false (busted seats)
 * rather than being conditionally composed, so layout above doesn't
 * shift the moment a seat busts mid-hand.
 */
@Composable
internal fun SeatBadgePill(
    text: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (text == null || !visible) {
        // Reserve no height — the surrounding column already controls
        // spacing via its own spacers, so an empty composable is
        // preferable to a transparent placeholder that would push the
        // stack number down a row.
        return
    }
    Text(
        text = text,
        typography = AppTheme.typography.Label.L300,
        color = AppTheme.colors.textSecondary,
        modifier = modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceTertiary.color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
