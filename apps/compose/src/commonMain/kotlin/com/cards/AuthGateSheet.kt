package com.dangerfield.cards

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
import com.dangerfield.cards.libraries.navigation.GateReason
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

/**
 * Shared "you need an account for this" sheet the router substitutes for a
 * gated route ([com.dangerfield.cards.libraries.navigation.AuthGateRoute]). The
 * [reason] (from [com.dangerfield.cards.libraries.navigation.AuthGateChecker])
 * picks the copy + CTA:
 *  - [GateReason.FinishingSetup] — a degraded guest whose account is still being
 *    created; it self-heals, so we just ask them to wait.
 *  - [GateReason.NeedAccount] — no account; offer to get started (onboarding).
 *  - [GateReason.NeedClaimedAccount] — a guest doing something that needs a
 *    claimed account; offer to save it.
 *
 * Pure / callback-driven so the host wires the CTAs to navigation.
 */
@Composable
fun AuthGateSheet(
    reason: GateReason,
    onCreateAccount: () -> Unit,
    onSaveAccount: () -> Unit,
    onDismiss: () -> Unit,
    state: DialogState = rememberDialogState(),
) {
    val copy = reason.copy()
    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = copy.emoji),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimension.D800),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = copy.title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D300))
            Text(
                text = copy.body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D800))

            val primaryAction = when (reason) {
                GateReason.FinishingSetup -> onDismiss
                GateReason.NeedAccount -> onCreateAccount
                GateReason.NeedClaimedAccount -> onSaveAccount
            }
            ButtonPrimary(
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(copy.primaryCta)
            }

            // Degraded "wait a moment" has no secondary path — the single
            // "Got it" already dismisses.
            if (reason != GateReason.FinishingSetup) {
                Spacer(Modifier.height(Dimension.D300))
                Button(
                    onClick = onDismiss,
                    type = ButtonType.Ghost,
                    style = ButtonStyle.Text,
                ) {
                    Text("Not now")
                }
            }
        }
    }
}

private data class GateCopy(
    val emoji: String,
    val title: String,
    val body: String,
    val primaryCta: String,
)

private fun GateReason.copy(): GateCopy = when (this) {
    GateReason.FinishingSetup -> GateCopy(
        emoji = "⏳",
        title = "Finishing setup",
        body = "We're still setting up your account — try again in a moment. We'll keep retrying in the background.",
        primaryCta = "Got it",
    )
    GateReason.NeedAccount -> GateCopy(
        emoji = "🪪",
        title = "Account needed",
        body = "Create an account to play with others.",
        primaryCta = "Get started",
    )
    GateReason.NeedClaimedAccount -> GateCopy(
        emoji = "🔒",
        title = "Save your account",
        body = "Save your account first so this stays yours across devices.",
        primaryCta = "Save account",
    )
}
