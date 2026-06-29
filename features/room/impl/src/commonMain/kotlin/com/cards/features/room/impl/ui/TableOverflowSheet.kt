package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_overflow_hand_label
import cards.libraries.resources.generated.resources.room_overflow_hand_value
import cards.libraries.resources.generated.resources.room_overflow_room_code_label
import cards.libraries.resources.generated.resources.room_overflow_session_label
import cards.libraries.resources.generated.resources.room_overflow_session_value
import cards.libraries.resources.generated.resources.room_overflow_stack_label
import cards.libraries.resources.generated.resources.room_overflow_title
import cards.libraries.resources.generated.resources.room_overflow_wallet_label
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The "…" overflow sheet — the at-a-glance table facts the declutter pulled out of
 * the always-on chrome: where the hand is, how the session is going, the player's
 * balances, and the room code to invite a friend mid-game (the lobby's code
 * surface is gone by then). Hand rankings live on a board tap instead, so this
 * stays a lean info panel, not a help screen.
 */
@Composable
internal fun TableOverflowSheet(
    handNumber: Int,
    street: BettingRound,
    sessionWon: Int,
    sessionLost: Int,
    walletChips: Long?,
    tableStack: Long,
    roomCode: String?,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.surface,
        showCloseButton = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.room_overflow_title),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            OverflowRow(
                label = stringResource(Res.string.room_overflow_hand_label),
                value = stringResource(
                    Res.string.room_overflow_hand_value,
                    handNumber,
                    stringResource(streetLabelResourceFor(street)),
                ),
            )
            OverflowRow(
                label = stringResource(Res.string.room_overflow_session_label),
                value = stringResource(Res.string.room_overflow_session_value, sessionWon, sessionLost),
            )
            if (walletChips != null) {
                OverflowChipRow(label = stringResource(Res.string.room_overflow_wallet_label), amount = walletChips)
            }
            OverflowChipRow(label = stringResource(Res.string.room_overflow_stack_label), amount = tableStack)
            if (!roomCode.isNullOrBlank()) {
                OverflowRow(label = stringResource(Res.string.room_overflow_room_code_label), value = roomCode)
            }
        }
    }
}

@Composable
private fun OverflowRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.content,
        )
    }
}

@Composable
private fun OverflowChipRow(label: String, amount: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier.weight(1f),
        )
        ChipCoinAmount(
            amount = amount,
            coinSize = 14.dp,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.content,
            gap = 5.dp,
            formatter = ::formatCompactChips,
        )
    }
}

@Preview
@Composable
private fun TableOverflowSheetPreview() {
    PreviewContent {
        TableOverflowSheet(
            handNumber = 7,
            street = BettingRound.Flop,
            sessionWon = 3,
            sessionLost = 5,
            walletChips = 12_400,
            tableStack = 1_840,
            roomCode = "AB12CD",
            onDismiss = {},
        )
    }
}
