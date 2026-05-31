package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD200
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD500

/**
 * Reusable selectable card that supports optional icons, badges, and expandable content.
 */
@Composable
fun SelectableCard(
    title: String,
    description: String,
    icon: IconResource?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
    expandedContent: @Composable (() -> Unit)? = null
) {
    val borderColor = if (isSelected) {
        AppTheme.colors.accentPrimary.color
    } else {
        AppTheme.colors.border.color
    }

    val cardModifier = modifier
        .fillMaxWidth()
        .border(2.dp, borderColor, Radii.R400.shape)
        .then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .padding(vertical = Dimension.D800, horizontal = Dimension.D500)

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(Dimension.D500)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon)
                HorizontalSpacerD200()
            }
            Text(
                title,
                typography = AppTheme.typography.Label.L600
            )

            Spacer(Modifier.weight(1f, fill = true))

            if (badge != null) {
                Text(
                    text = badge,
                    modifier = Modifier
                        .background(AppTheme.colors.surfaceHigh.color, Radii.R300.shape)
                        .padding(horizontal = Dimension.D300, vertical = Dimension.D100),
                    typography = AppTheme.typography.Caption.C300
                )
            }
        }

        Text(
            description,
            typography = AppTheme.typography.Caption.C400.Italic
        )

        if (expandedContent != null) {
            AnimatedVisibility(isSelected) {
                Column {
                    VerticalSpacerD500()
                    expandedContent()
                }
            }
        }
    }
}
