package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * One row that fronts every shelf on Home — title on the left, an
 * optional "See all" link on the right. Used so the four sections
 * (Take a seat, Friends, Recent achievements, Recently played with)
 * read as siblings: same typography, same spacing, same tap shape.
 *
 * Earlier iteration had "Take a seat" using a bespoke `SectionLabel`
 * at a different size than the three strips, so the home screen felt
 * stitched together. Consolidating here pins it.
 *
 * When [trailingLabel] is non-null and [onClick] is provided, the
 * whole row becomes the tap target — matches Apple's pattern where
 * the whole section header is a shortcut to the underlying page.
 * When `trailingLabel == null`, the right side is empty.
 *
 * Lives in this feature module for now; lift to `:libraries:ui` if
 * Shop / Profile / etc. want the exact same shape (their current
 * `SectionHeader` privates are similar but each takes a slightly
 * different shape).
 */
@Composable
internal fun SectionHeader(
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
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        SectionHeader(title = "Take a seat")
    }
}

@Preview
@Composable
private fun SectionHeaderPreview_WithSeeAll() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        SectionHeader(title = "Friends", trailingLabel = "3 online · See all", onClick = {})
    }
}
