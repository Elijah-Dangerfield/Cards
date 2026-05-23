package com.dangerfield.cards.features.home.impl

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.dialogEmoji
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.resolveAvatarBackground
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

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
    val chipGold = remember { ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold") }

    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        emoji = dialogEmoji(
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
                text = "Welcome,",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D200))

            Text(
                text = displayName,
                typography = AppTheme.typography.Heading.H900,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D600))
            AnimatedChipReveal(
                amount = chips,
                color = chipGold,
            )
            Spacer(Modifier.height(Dimension.D1000))
            Text(
                text = "Here's a little gift from us to start with.",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimension.D200))
            Text(
                text = "Use these to play against other players and buy upgrades.",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimension.D500))
            // Sets expectations for the silent welcome-week daily grant
            // (see Wallet.WELCOME_WEEK_*). The grant lands every wallet
            // contact post-signup-day with no in-app dialog, so this is
            // the *only* place the user learns the daily +500 is a thing.
            Text(
                text = "Open the app every day this week — we'll add another 500 chips, on us.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimension.D1000))

            ButtonPrimary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Let's play")
            }
        }
    }
}

/**
 * Hero chip count for the welcome dialog — gold coin + odometer-style
 * tween from 0 to [amount] on first composition. Bespoke (not
 * [com.dangerfield.cards.libraries.ui.components.AnimatedNumberText])
 * because that primitive intentionally suppresses animation during its
 * mount-settle window to avoid "0 → real" flashes on tab switches; here
 * we *want* the 0 → real reveal to read as a "you just got these chips"
 * moment.
 */
@Composable
private fun AnimatedChipReveal(
    amount: Long,
    color: ColorResource,
) {
    val animated = remember { Animatable(initialValue = 0f) }
    var displayed by remember { mutableStateOf(0L) }
    LaunchedEffect(amount) {
        animated.animateTo(
            targetValue = amount.toFloat(),
            animationSpec = tween(
                durationMillis = 1_100,
                easing = FastOutSlowInEasing,
            ),
        ) {
            displayed = this.value.toLong()
        }
        // Pin the final value exactly — the tween's last frame can land a
        // hair short of the target due to float→long truncation.
        displayed = amount
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChipCoin(
            size = 40.dp,
            textTypography = AppTheme.typography.Heading.H700,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatWithThousands(displayed),
            typography = AppTheme.typography.Display.D1100,
            color = color,
        )
    }
}

private fun formatWithThousands(value: Long): String {
    val s = value.toString()
    val sb = StringBuilder()
    val len = s.length
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
}

@org.jetbrains.compose.ui.tooling.preview.Preview
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

@org.jetbrains.compose.ui.tooling.preview.Preview
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

@org.jetbrains.compose.ui.tooling.preview.Preview
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
