package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_welcome_dialog_chip_use_line
import cards.libraries.resources.generated.resources.home_welcome_dialog_gift_line
import cards.libraries.resources.generated.resources.home_welcome_dialog_greeting
import cards.libraries.resources.generated.resources.home_welcome_dialog_primary_cta
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AnimatedChipReveal
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.resolveAvatarBackground
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * One-shot starter-grant intro shown the first time a new account lands
 * on home. Welcomes the user by display name, surfaces the (server-driven)
 * chip grant they just received as a chunky chip-gold hero number, and
 * sets expectations about where those chips can be spent.
 *
 * The emoji bubble carries the user's chosen avatar emoji + background
 * color so the dialog feels personal from the first frame. Color
 * resolution routes through `resolveAvatarBackground` so the bubble, the
 * Profile screen's `AvatarCircle`, and the Edit Profile hero all paint
 * the same fill for the same `#rrggbb` input. The server seeds a real
 * palette value at profile create time; null only fires for malformed
 * input on read, which falls back to the DS `surfaceSecondary`.
 */
@Composable
internal fun WelcomeDialog(
    displayName: String,
    avatarEmoji: String,
    avatarBackgroundColorHex: String?,
    chips: Long,
    onDismiss: () -> Unit,
    state: DialogState = rememberDialogState(),
) {
    val resolvedAvatarBg = resolveAvatarBackground(avatarBackgroundColorHex)
    val bubbleSurface: BubbleSurface = remember(resolvedAvatarBg) {
        BubbleSurface.Solid(
            color = ColorResource.FromColor(resolvedAvatarBg, "user-avatar-bg"),
        )
    }
    val chipGold = AppTheme.colors.poker.chipGold

    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(
            emoji = avatarEmoji.ifBlank { "🎉" },
            surface = bubbleSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = Dimension.D800,
                    start = Dimension.D800,
                    end = Dimension.D800,
                    bottom = Dimension.D800,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(Res.string.home_welcome_dialog_greeting),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D200))

            Text(
                text = displayName,
                typography = AppTheme.typography.Heading.H900,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D600))
            AnimatedChipReveal(
                amount = chips,
                color = chipGold,
            )
            Spacer(Modifier.height(Dimension.D1000))
            Text(
                text = stringResource(Res.string.home_welcome_dialog_gift_line),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimension.D200))
            Text(
                text = stringResource(Res.string.home_welcome_dialog_chip_use_line),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimension.D1000))

            ButtonPrimary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.home_welcome_dialog_primary_cta))
            }
        }
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_WithHex() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Bubbly-Triangle-223",
            avatarEmoji = "🦊",
            avatarBackgroundColorHex = "#F6B26B",
            chips = 10_000L,
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_NoHex() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Anxious-Mouse-599",
            avatarEmoji = "🐺",
            avatarBackgroundColorHex = null,
            chips = 10_000L,
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_LargerGrant() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Purple-Whale-556",
            avatarEmoji = "🐳",
            avatarBackgroundColorHex = "#8E7CC3",
            chips = 25_000L,
            onDismiss = {},
        )
    }
}

