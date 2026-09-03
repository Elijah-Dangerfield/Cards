package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_top_bar_back_a11y
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * The shared header for every rooms screen — a circular back button on the
 * left, an absolutely-centered [title] (+ optional [sub]) that stays centered
 * regardless of the side widths, and an optional [right] slot (typically a
 * [VisTag]). This is the Compose port of the handoff's `RHeader`.
 *
 * Centering is independent of the back / right content widths so the title
 * reads as the screen's anchor, matching the public/private room mocks.
 */
@Composable
fun RoomHeader(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = Dimension.D700, vertical = Dimension.D300),
        contentAlignment = Alignment.Center,
    ) {
        // Centered title block — capped so a long title can't collide with the
        // side controls.
        Column(
            modifier = Modifier.widthIn(max = 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            sub?.let {
                Text(
                    text = it,
                    typography = AppTheme.typography.Caption.C300,
                    color = AppTheme.colors.contentTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onNavigateBack != null) {
                IconButton(
                    size = IconButton.Size.Medium,
                    icon = Icons.ChevronLeft(stringResource(Res.string.ui_top_bar_back_a11y)),
                    onClick = onNavigateBack,
                )
            } else {
                Box(Modifier) // keep title centered when there's no back button
            }
            right?.invoke() ?: Box(Modifier)
        }
    }
}

@Preview
@Composable
private fun RoomHeaderPreview() {
    PreviewContent {
        Column {
            RoomHeader(
                title = "The Felt",
                sub = "Public table",
                onNavigateBack = {},
                right = { VisTag(kind = RoomVisibility.Public) },
            )
            RoomHeader(
                title = "Create a room",
                onNavigateBack = {},
                right = { VisTag(kind = RoomVisibility.Private) },
            )
        }
    }
}
