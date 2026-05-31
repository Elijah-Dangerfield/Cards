package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.system.AppTheme

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
    StatusPill(
        text = text,
        background = AppTheme.colors.surfaceHigh,
        foreground = AppTheme.colors.contentSecondary,
        typography = AppTheme.typography.Label.L300,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = modifier,
    )
}
