package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.achievement_unlocked_dialog_continue_cta
import cards.libraries.resources.generated.resources.achievement_unlocked_dialog_heading
import cards.libraries.resources.generated.resources.achievement_unlocked_dialog_registry_drift_fallback
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementUnlockReveal
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Dialog destination content for [com.dangerfield.cards.features.room.AchievementUnlockedRoute].
 *
 * Renders the [AchievementUnlockReveal] sequence inside the DS
 * [Dialog] surface so it lands as a slides-up-with-scrim floating
 * window above whatever screen triggered the unlock. Resolves the
 * achievement from its serialized id name; if the id is unknown
 * (registry drift) the dialog renders an apologetic fallback and
 * stays dismissable so the user is never stuck.
 *
 * Generic by design: tutorial completion is the first consumer but
 * level-ups, bot-whisperer capstones, and other future unlock
 * moments can navigate here without rebuilding the celebration UI.
 */
@Composable
internal fun AchievementUnlockedDialog(
    achievementId: String,
    onDismiss: () -> Unit,
    state: DialogState = rememberDialogState(),
) {
    val achievement: Achievement? = AchievementId.entries
        .firstOrNull { it.name == achievementId }
        ?.let { AllAchievementsById[it] }

    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji("🏆")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = stringResource(Res.string.achievement_unlocked_dialog_heading),
                typography = AppTheme.typography.Display.D1100,
                color = ColorResource.Amber500,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD500()
            if (achievement == null) {
                // Registry drift: an id arrived that the client doesn't
                // know about. Don't crash the celebration; show a
                // generic congrats and a way out.
                Text(
                    text = stringResource(Res.string.achievement_unlocked_dialog_registry_drift_fallback),
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(modifier = Modifier.size(180.dp)) {
                    AchievementUnlockReveal(achievement = achievement)
                }
                VerticalSpacerD800()
                Text(
                    text = achievement.name,
                    typography = AppTheme.typography.Heading.H800.Italic,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                )
                if (achievement.description.isNotBlank()) {
                    VerticalSpacerD500()
                    Text(
                        text = achievement.description,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = onDismiss,
                size = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.achievement_unlocked_dialog_continue_cta))
            }

            VerticalSpacerD1000()
        }
    }
}

@Preview
@Composable
private fun AchievementUnlockedDialogPreview_TutorialComplete() {
    PreviewContent {
        AchievementUnlockedDialog(
            achievementId = AchievementId.TUTORIAL_COMPLETE.name,
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun AchievementUnlockedDialogPreview_Unknown() {
    PreviewContent {
        AchievementUnlockedDialog(
            achievementId = "REGISTRY_DRIFT_PLACEHOLDER",
            onDismiss = {},
        )
    }
}
