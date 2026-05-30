package com.dangerfield.cards.libraries.ui.components.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Title row that fronts every shelf-style section — large title on the
 * left, optional secondary label on the right. Used so sibling shelves
 * across a surface (Home's "Take a seat" / Friends / Recent achievements
 * / Recently played with) read as siblings: same typography, spacing,
 * tap shape.
 *
 * When [trailingLabel] is non-null and [onClick] is provided, the whole
 * row becomes the tap target — matches Apple's pattern where a section
 * header is a shortcut to its underlying page. When `trailingLabel ==
 * null`, the right side is empty.
 *
 * Pair with a vertical spacer (D500) before the section's content.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
        if (trailingLabel != null) {
            Text(
                text = trailingLabel,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

@Preview
@Composable
private fun SectionHeaderPreview_Plain() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        SectionHeader(title = "Take a seat")
    }
}

@Preview
@Composable
private fun SectionHeaderPreview_WithSeeAll() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        SectionHeader(
            title = "Friends",
            trailingLabel = "3 online · See all",
            onClick = {},
        )
    }
}
