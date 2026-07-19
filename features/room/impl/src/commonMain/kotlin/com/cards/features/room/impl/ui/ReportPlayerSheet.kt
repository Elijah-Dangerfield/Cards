package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.report_cancel_button
import cards.libraries.resources.generated.resources.report_details_hint
import cards.libraries.resources.generated.resources.report_reason_cheating
import cards.libraries.resources.generated.resources.report_reason_harassment
import cards.libraries.resources.generated.resources.report_reason_offensive_name
import cards.libraries.resources.generated.resources.report_reason_other
import cards.libraries.resources.generated.resources.report_reason_spam
import cards.libraries.resources.generated.resources.report_sheet_subtitle
import cards.libraries.resources.generated.resources.report_sheet_title
import cards.libraries.resources.generated.resources.report_submit_button
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.chip.SelectChip
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The report flow's reason picker (MOD-2). Opened from the player-profile
 * sheet's Report action: the reporter selects one or more reason tags and can
 * add optional free-text detail, then submits. Selecting at least one reason
 * enables the submit — the categories + trimmed details ride to the server so a
 * moderator can filter reports by category.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportPlayerSheet(
    seat: SeatView,
    onDismiss: () -> Unit,
    onSubmit: (categories: List<String>, details: String?) -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    var details by remember { mutableStateOf("") }

    BottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = topAccessoryEmoji(emoji = ReportFlagEmoji).asDragHandle(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimension.D500),
        ) {
            Text(
                text = stringResource(Res.string.report_sheet_title, seat.displayName),
                typography = AppTheme.typography.Heading.H700,
            )
            VerticalSpacerD200()
            Text(
                text = stringResource(Res.string.report_sheet_subtitle),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
            VerticalSpacerD400()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
                verticalArrangement = Arrangement.spacedBy(Dimension.D400),
            ) {
                ReportReasons.forEach { reason ->
                    SelectChip(
                        label = stringResource(reason.labelRes),
                        selected = reason.key in selected,
                        onClick = {
                            if (reason.key in selected) selected.remove(reason.key) else selected.add(reason.key)
                        },
                    )
                }
            }
            VerticalSpacerD400()
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(Res.string.report_details_hint)) },
                singleLine = false,
            )
            VerticalSpacerD500()
            ButtonPrimary(
                onClick = { onSubmit(selected.toList(), details.trim().takeIf { it.isNotBlank() }) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.report_submit_button))
            }
            VerticalSpacerD200()
            ButtonSecondary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.report_cancel_button))
            }
        }
    }
}

private data class ReportReason(val key: String, val labelRes: StringResource)

private val ReportReasons = listOf(
    ReportReason("harassment", Res.string.report_reason_harassment),
    ReportReason("cheating", Res.string.report_reason_cheating),
    ReportReason("offensive_name", Res.string.report_reason_offensive_name),
    ReportReason("spam", Res.string.report_reason_spam),
    ReportReason("other", Res.string.report_reason_other),
)

private const val ReportFlagEmoji = "🚩"

@Preview
@Composable
private fun ReportPlayerSheetPreview() {
    PreviewContent {
        ReportPlayerSheet(
            seat = SeatView(
                index = 1,
                displayName = "Riverboat",
                stack = 1_000,
                contributedThisStreet = 0,
                isActing = false,
                isHuman = false,
                isBot = false,
                avatarKey = null,
                emoji = "🎩",
                holeCards = emptyList(),
                showHoleCardBacks = false,
                participation = com.dangerfield.cards.libraries.gameplay.HandParticipation.InHand,
                seatEmpty = false,
                isBusted = false,
                lastAction = null,
                isDealer = false,
                isSmallBlind = false,
                isBigBlind = false,
            ),
            onDismiss = {},
            onSubmit = { _, _ -> },
        )
    }
}
