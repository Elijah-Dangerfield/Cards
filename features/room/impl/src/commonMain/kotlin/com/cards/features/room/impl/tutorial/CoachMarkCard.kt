package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400

/**
 * Bottom-anchored coach-mark card. Sits above the action bar (or in its
 * place when there's no action prompt) so it overlays the empty space of
 * the table rather than pushing on opponents / board / hero cards.
 *
 * For "Continue" steps (narration), shows a CTA button on the card itself.
 * For action steps (Raise / Call / Check / Fold), the action bar serves
 * as the CTA so the card has no button.
 */
@Composable
internal fun CoachMarkCard(
    mark: CoachMark,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .border(1.dp, AppTheme.colors.borderSecondary.color, Radii.Card.shape)
            .padding(Dimension.D600),
        horizontalAlignment = Alignment.Start,
    ) {
        if (!mark.title.isNullOrBlank()) {
            Text(
                text = mark.title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD200()
        }
        Text(
            text = mark.body,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        if (!mark.ctaLabel.isNullOrBlank()) {
            VerticalSpacerD400()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ButtonPrimary(
                    onClick = onContinue,
                    size = ButtonSize.Medium,
                ) {
                    Text(mark.ctaLabel)
                }
            }
        }
    }
}
