package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_play_style_unlocked_body
import cards.libraries.resources.generated.resources.home_play_style_unlocked_primary_cta
import cards.libraries.resources.generated.resources.home_play_style_unlocked_secondary_cta
import cards.libraries.resources.generated.resources.home_play_style_unlocked_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource

/**
 * One-shot "your play style is unlocked" celebration (PROG-6), surfaced the
 * first time the user crosses the play-style sample threshold. Mirrors the
 * starter-grant [WelcomeDialog] shape — a routed, dismissible dialog with a
 * top-edge emoji bubble. The primary CTA routes to Stats (where the unlocked
 * radar lives); "Later" just dismisses.
 */
@Composable
internal fun PlayStyleUnlockedDialog(
    onSeeStyle: () -> Unit,
    onDismiss: () -> Unit,
    state: DialogState = rememberDialogState(),
) {
    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "📊"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimension.D800),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(Res.string.home_play_style_unlocked_title),
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D500))
            Text(
                text = stringResource(Res.string.home_play_style_unlocked_body),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D1000))
            ButtonPrimary(
                onClick = onSeeStyle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.home_play_style_unlocked_primary_cta))
            }
            Spacer(Modifier.height(Dimension.D300))
            ButtonSecondary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.home_play_style_unlocked_secondary_cta))
            }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PlayStyleUnlockedDialogPreview() {
    PreviewContent {
        PlayStyleUnlockedDialog(onSeeStyle = {}, onDismiss = {})
    }
}
