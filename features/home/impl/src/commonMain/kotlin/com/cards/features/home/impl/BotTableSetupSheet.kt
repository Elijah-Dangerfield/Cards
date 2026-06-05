package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_bot_setup_balance_disclaimer
import cards.libraries.resources.generated.resources.home_bot_setup_difficulty_label
import cards.libraries.resources.generated.resources.home_bot_setup_seat_summary_heads_up
import cards.libraries.resources.generated.resources.home_bot_setup_seat_summary_other
import cards.libraries.resources.generated.resources.home_bot_setup_seats_label
import cards.libraries.resources.generated.resources.home_bot_setup_start_button
import cards.libraries.resources.generated.resources.home_bot_setup_subtitle_four
import cards.libraries.resources.generated.resources.home_bot_setup_subtitle_heads_up
import cards.libraries.resources.generated.resources.home_bot_setup_subtitle_other
import cards.libraries.resources.generated.resources.home_bot_setup_subtitle_six
import cards.libraries.resources.generated.resources.home_bot_setup_title
import com.dangerfield.cards.libraries.ui.components.OptionPillRow
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource

/**
 * Pre-game configuration sheet for the Practice (bot) table. Picks both
 * difficulty and seat count in one place — Home's Practice hero opens this
 * directly, no second picker. Stake values derive from the chosen difficulty
 * downstream
 * ([PlayPokerFeatureEntryPoint][com.dangerfield.cards.features.room.impl.PlayPokerFeatureEntryPoint]).
 *
 * A bottom sheet (per the Play-section rebuild) rather than a centered dialog,
 * so every play option resolves to a sheet hosted on Home.
 */
@Composable
internal fun BotTableSetupSheet(
    onStart: (difficulty: String, seatCount: Int) -> Unit,
    onDismiss: () -> Unit,
    initialDifficulty: String = "Standard",
) {
    var difficulty by remember { mutableStateOf(initialDifficulty) }
    var seatCount by remember { mutableStateOf(4) }
    BottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = topAccessoryEmoji(
            emoji = "♠",
            surface = BubbleSurface.Solid(AppTheme.colors.surfaceHigh),
        ).asDragHandle(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.home_bot_setup_title),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD300()
            Text(
                text = subtitleFor(seatCount, difficulty),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
            VerticalSpacerD800()
            // Difficulty first — it changes the table's feel; seat count is
            // the lighter follow-up choice. Difficulty values ("Casual" /
            // "Standard" / "Challenging") double as wire keys consumed by
            // `PlayPokerFeatureEntryPoint`'s downstream routing.
            Text(
                text = stringResource(Res.string.home_bot_setup_difficulty_label),
                typography = AppTheme.typography.Label.L400,
                color = AppTheme.colors.contentSecondary,
            )
            VerticalSpacerD300()
            OptionPillRow(
                options = listOf("Casual", "Standard", "Challenging"),
                selected = difficulty,
                onSelect = { difficulty = it },
                label = { it },
            )
            VerticalSpacerD500()
            Text(
                text = stringResource(Res.string.home_bot_setup_seats_label),
                typography = AppTheme.typography.Label.L400,
                color = AppTheme.colors.contentSecondary,
            )
            VerticalSpacerD300()
            OptionPillRow(
                options = listOf(2, 4, 6),
                selected = seatCount,
                onSelect = { seatCount = it },
                label = { "$it" },
            )
            VerticalSpacerD500()
            Text(
                text = seatSummaryLabel(seatCount),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD500()
            Text(
                text = stringResource(Res.string.home_bot_setup_balance_disclaimer),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = { onStart(difficulty, seatCount) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.home_bot_setup_start_button))
            }
        }
    }
}

@Composable
private fun subtitleFor(seatCount: Int, difficulty: String): String = when (seatCount) {
    2 -> stringResource(Res.string.home_bot_setup_subtitle_heads_up, difficulty)
    4 -> stringResource(Res.string.home_bot_setup_subtitle_four, difficulty)
    6 -> stringResource(Res.string.home_bot_setup_subtitle_six, difficulty)
    else -> stringResource(Res.string.home_bot_setup_subtitle_other, difficulty, seatCount - 1)
}

@Composable
private fun seatSummaryLabel(seatCount: Int): String =
    if (seatCount == 2) {
        stringResource(Res.string.home_bot_setup_seat_summary_heads_up, seatCount - 1)
    } else {
        stringResource(Res.string.home_bot_setup_seat_summary_other, seatCount - 1, seatCount)
    }

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun BotTableSetupSheetPreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        BotTableSetupSheet(
            onStart = { _, _ -> },
            onDismiss = {},
        )
    }
}
