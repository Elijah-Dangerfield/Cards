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
import cards.libraries.resources.generated.resources.home_welcome_dialog_feedback_cta
import cards.libraries.resources.generated.resources.home_welcome_dialog_founding_body
import cards.libraries.resources.generated.resources.home_welcome_dialog_founding_feedback_line
import cards.libraries.resources.generated.resources.home_welcome_dialog_gift_line
import cards.libraries.resources.generated.resources.home_welcome_dialog_grant_pending_line
import cards.libraries.resources.generated.resources.home_welcome_dialog_greeting
import cards.libraries.resources.generated.resources.home_welcome_dialog_primary_cta
import cards.libraries.resources.generated.resources.home_welcome_dialog_review_cta
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AnimatedChipReveal
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
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
 * The one-time Home welcome dialog. Welcomes the user by display name, and adapts
 * its body to the moment the arbiter resolved:
 *
 * - **Starter-grant reveal** — [grantChips] non-null animates the exact figure as
 *   a chip-gold hero number; [grantPending] instead promises the chips are
 *   landing (offline / pre-config, so we never animate a wrong or zero number).
 * - **Founding-member window** — [isFounding] layers on the thank-you copy and
 *   two opt-in asks ([onGiveReview], [onGiveFeedback]) that are make-or-break
 *   while the player base is tiny. Play is always the primary, unblocked path
 *   out ([onDismiss]).
 *
 * The emoji bubble carries the user's chosen avatar emoji + background color so
 * the dialog feels personal from the first frame; color resolution routes through
 * `resolveAvatarBackground` so the bubble matches the Profile avatar for the same
 * `#rrggbb` input, falling back to the DS `surfaceSecondary` on malformed input.
 */
@Composable
internal fun WelcomeDialog(
    displayName: String,
    avatarEmoji: String,
    avatarBackgroundColorHex: String?,
    grantChips: Long?,
    grantPending: Boolean,
    isFounding: Boolean,
    onGiveReview: () -> Unit,
    onGiveFeedback: () -> Unit,
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
                .padding(all = Dimension.D800),
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

            when {
                grantChips != null -> {
                    Spacer(Modifier.height(Dimension.D600))
                    AnimatedChipReveal(amount = grantChips, color = chipGold)
                    Spacer(Modifier.height(Dimension.D1000))
                    Text(
                        text = stringResource(Res.string.home_welcome_dialog_gift_line),
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.content,
                        textAlign = TextAlign.Center,
                    )
                }
                grantPending -> {
                    Spacer(Modifier.height(Dimension.D800))
                    Text(
                        text = stringResource(Res.string.home_welcome_dialog_grant_pending_line),
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.content,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (isFounding) {
                FoundingSection(
                    hasGrantSection = grantChips != null || grantPending,
                    onGiveReview = onGiveReview,
                    onGiveFeedback = onGiveFeedback,
                    onDismiss = onDismiss,
                )
            } else {
                // Non-founding is always a starter-grant reveal (that's the only
                // way to be eligible without the window), so the chip-use line
                // always follows.
                Spacer(Modifier.height(Dimension.D200))
                Text(
                    text = stringResource(Res.string.home_welcome_dialog_chip_use_line),
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Dimension.D1000))
                ButtonPrimary(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(Res.string.home_welcome_dialog_primary_cta))
                }
            }
        }
    }
}

@Composable
private fun FoundingSection(
    hasGrantSection: Boolean,
    onGiveReview: () -> Unit,
    onGiveFeedback: () -> Unit,
    onDismiss: () -> Unit,
) {
    Spacer(Modifier.height(if (hasGrantSection) Dimension.D600 else Dimension.D800))
    Text(
        text = stringResource(Res.string.home_welcome_dialog_founding_body),
        typography = AppTheme.typography.Body.B600,
        color = AppTheme.colors.content,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Dimension.D400))
    Text(
        text = stringResource(Res.string.home_welcome_dialog_founding_feedback_line),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Dimension.D1000))
    // Play stays the primary, unblocked path out; the review + feedback asks sit
    // above it as visible-but-optional secondaries. No dark-pattern nudging.
    ButtonSecondary(onClick = onGiveReview, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(Res.string.home_welcome_dialog_review_cta))
    }
    Spacer(Modifier.height(Dimension.D300))
    ButtonGhost(onClick = onGiveFeedback, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(Res.string.home_welcome_dialog_feedback_cta))
    }
    Spacer(Modifier.height(Dimension.D300))
    ButtonPrimary(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(Res.string.home_welcome_dialog_primary_cta))
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_FoundingWithGrant() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Bubbly-Triangle-223",
            avatarEmoji = "🦊",
            avatarBackgroundColorHex = "#F6B26B",
            grantChips = 10_000L,
            grantPending = false,
            isFounding = true,
            onGiveReview = {},
            onGiveFeedback = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_FoundingGrantPending() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Anxious-Mouse-599",
            avatarEmoji = "🐺",
            avatarBackgroundColorHex = null,
            grantChips = null,
            grantPending = true,
            isFounding = true,
            onGiveReview = {},
            onGiveFeedback = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_FoundingExistingPlayer() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Purple-Whale-556",
            avatarEmoji = "🐳",
            avatarBackgroundColorHex = "#8E7CC3",
            grantChips = null,
            grantPending = false,
            isFounding = true,
            onGiveReview = {},
            onGiveFeedback = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun WelcomeDialogPreview_PlainWelcome() {
    PreviewContent {
        WelcomeDialog(
            displayName = "Calm-Otter-104",
            avatarEmoji = "🦦",
            avatarBackgroundColorHex = "#6FA8DC",
            grantChips = 10_000L,
            grantPending = false,
            isFounding = false,
            onGiveReview = {},
            onGiveFeedback = {},
            onDismiss = {},
        )
    }
}
