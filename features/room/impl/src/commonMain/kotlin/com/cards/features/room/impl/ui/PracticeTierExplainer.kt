package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_practice_tier_explainer_body
import cards.libraries.resources.generated.resources.room_practice_tier_explainer_bots_only_body
import cards.libraries.resources.generated.resources.room_practice_tier_explainer_bots_only_subsidized_body
import cards.libraries.resources.generated.resources.room_practice_tier_explainer_subsidized_body
import cards.libraries.resources.generated.resources.room_practice_tier_explainer_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Explains the "Practice tier · bots present" label — opened by tapping the
 * pill and auto-shown once at game start so the player knows the cost before
 * they play. A bot-stacked / under-filled MP table halves XP (0.5x) and locks
 * multiplayer-only achievements; this dialog says so in plain language instead
 * of leaving the pill a silent downgrade.
 *
 * [subsidized] (a public disclosed-bot table where chips are real) swaps the
 * main body off the "the hand counts as practice" framing — which contradicts
 * chips being at stake — onto one that only halves XP / locks MP achievements
 * and says the chips are real. True practice tables keep the original body.
 *
 * When [botsOnly] (the local player is the only human at the table) it adds a
 * chips line: normally that real chips aren't at stake (private practice bots),
 * but when [subsidized] that you keep what you win against the bots, up to a
 * daily limit — the house-funded subsidy.
 */
@Composable
internal fun PracticeTierExplainer(
    botsOnly: Boolean,
    subsidized: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        title = stringResource(Res.string.room_practice_tier_explainer_title),
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🤖"),
        itemSpacing = Dimension.D700,
    ) {
        Text(
            text = stringResource(
                if (subsidized) {
                    Res.string.room_practice_tier_explainer_subsidized_body
                } else {
                    Res.string.room_practice_tier_explainer_body
                },
            ),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
        if (botsOnly) {
            Text(
                text = stringResource(
                    if (subsidized) {
                        Res.string.room_practice_tier_explainer_bots_only_subsidized_body
                    } else {
                        Res.string.room_practice_tier_explainer_bots_only_body
                    },
                ),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun PracticeTierExplainerPreview() {
    PreviewContent {
        PracticeTierExplainer(botsOnly = false, subsidized = false, onDismiss = {})
    }
}

@Preview
@Composable
private fun PracticeTierExplainerPreview_BotsOnly() {
    PreviewContent {
        PracticeTierExplainer(botsOnly = true, subsidized = false, onDismiss = {})
    }
}

@Preview
@Composable
private fun PracticeTierExplainerPreview_Subsidized() {
    PreviewContent {
        PracticeTierExplainer(botsOnly = false, subsidized = true, onDismiss = {})
    }
}

@Preview
@Composable
private fun PracticeTierExplainerPreview_SubsidizedBotsOnly() {
    PreviewContent {
        PracticeTierExplainer(botsOnly = true, subsidized = true, onDismiss = {})
    }
}
