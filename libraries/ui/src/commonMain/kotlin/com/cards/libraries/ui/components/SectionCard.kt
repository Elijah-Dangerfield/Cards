package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD500

/**
 * Standardized section surface used across screens for grouped content blocks.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    contentSpacing: Dp = Dimension.D400,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier) {
        Text(
            text = title,
            typography = AppTheme.typography.Heading.H700
        )

        VerticalSpacerD500()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surface.color, Radii.Card.shape)
                .padding(Dimension.D600),
            verticalArrangement = Arrangement.spacedBy(contentSpacing)
        ) {
            content()
        }
    }
}

/**
 * Compact label/value row for summary sections.
 */
@Composable
fun SummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            typography = AppTheme.typography.Caption.C400,
            color = AppTheme.colors.contentSecondary
        )
        VerticalSpacerD100()
        Text(
            text = value,
            typography = AppTheme.typography.Body.B600
        )
    }
}
