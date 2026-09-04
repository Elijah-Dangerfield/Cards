package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Generic "this isn't built yet" bottom sheet for Home CTAs that
 * point at unbuilt features (Quick Match before public-rooms exist,
 * Tournament for V2). Keeps the marketing-shaped surface up top
 * without making the V1 build pretend the feature works.
 *
 * Per voice-and-copy.md: no urgency, no begging, no "Join the
 * waitlist!". Just an honest "here's when this lands."
 */
@Composable
internal fun ComingSoonSheet(
    title: String,
    body: String,
    emoji: String,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        title = title,
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.surface,
        dragHandle = topAccessoryEmoji(emoji = emoji).asDragHandle(),
    ) {
        Text(
            text = body,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
private fun ComingSoonSheetPreview_QuickMatch() {
    PreviewContent {
        ComingSoonSheet(
            title = "Quick Match",
            body = "Public rooms are still in the oven. We'll flip this on once matchmaking is ready.",
            emoji = "⚡",
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun ComingSoonSheetPreview_Tournament() {
    PreviewContent {
        ComingSoonSheet(
            title = "Tournaments",
            body = "Coming in a future release once the league season cadence is locked in.",
            emoji = "🏆",
            onDismiss = {},
        )
    }
}
