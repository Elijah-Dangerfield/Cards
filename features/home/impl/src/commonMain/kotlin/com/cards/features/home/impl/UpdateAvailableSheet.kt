package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_update_body
import cards.libraries.resources.generated.resources.home_update_cta
import cards.libraries.resources.generated.resources.home_update_dismiss
import cards.libraries.resources.generated.resources.home_update_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * "There's a newer Downcard" sheet — the lowest-priority Home notification
 * (see `HomeNotification.UpdateAvailable`), shown at most once per feature
 * release and never for a patch.
 *
 * Deliberately the mildest surface we have. No urgency, no red badge, no
 * "required update" framing, and a plainly-labelled way out: nothing about a
 * newer version is an emergency, and this only ever takes a Home slot that no
 * celebration or shortfall wanted. Per voice-and-copy, it says what changed and
 * lets the player decide.
 */
@Composable
internal fun UpdateAvailableSheet(
    latestVersion: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        title = stringResource(Res.string.home_update_title),
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.background,
        dragHandle = topAccessoryEmoji(emoji = "🆕").asDragHandle(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.home_update_body, latestVersion),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
            Spacer(modifier = Modifier.height(Dimension.D600))

            ButtonPrimary(
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.home_update_cta))
            }
            Spacer(modifier = Modifier.height(Dimension.D200))
            ButtonGhost(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.home_update_dismiss))
            }
        }
    }
}

@Preview
@Composable
private fun UpdateAvailableSheetPreview() {
    PreviewContent {
        UpdateAvailableSheet(
            latestVersion = "0.3.0",
            onUpdate = {},
            onDismiss = {},
        )
    }
}
