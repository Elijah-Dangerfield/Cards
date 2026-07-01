package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_leave_bots_body
import cards.libraries.resources.generated.resources.room_leave_bots_leave_button
import cards.libraries.resources.generated.resources.room_leave_bots_stay_button
import cards.libraries.resources.generated.resources.room_leave_bots_subsidized_body
import cards.libraries.resources.generated.resources.room_leave_bots_title
import cards.libraries.resources.generated.resources.room_leave_forfeit_note
import cards.libraries.resources.generated.resources.room_leave_settle_even
import cards.libraries.resources.generated.resources.room_leave_settle_up
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Back-press confirmation for the bot table — surfaces "you'll lose this
 * hand" so the user doesn't drop a hand by accident swiping back. Always
 * shows when a hand is in progress; leaving is a real cost and warrants
 * an explicit confirmation every time.
 *
 * On a [subsidized] bots-for-chips table the body states the exact stack
 * cashing back to the wallet (MP-6) — the disclosed-bot complaint was that
 * the chips watched at the table didn't visibly follow the player out, so
 * the leave moment names the number going home.
 *
 * On a real-chip table ([showSettle]) the dialog also states the *net* this
 * leave settles to the wallet — `[netSettleChips]` = the stack returning minus
 * the buy-in — and, when the player still has chips committed in the live hand
 * ([forfeitedThisHand] > 0, e.g. a posted blind), calls out that those are
 * forfeited by leaving now (ROOM-4). Two players asked to "be super in control
 * of their money" at the leave moment; the chip math was already correct, this
 * makes the consequence visible before they tap Leave.
 */
@Composable
internal fun LeaveBotsConfirmDialog(
    onStay: () -> Unit,
    onLeave: () -> Unit,
    subsidized: Boolean = false,
    cashOutChips: Long = 0,
    showSettle: Boolean = false,
    netSettleChips: Long = 0,
    forfeitedThisHand: Long = 0,
) {
    Dialog(
        title = stringResource(Res.string.room_leave_bots_title),
        onDismissRequest = onStay,
        topAccessory = topAccessoryEmoji(emoji = "🚪"),
    ) {
        // Body slot: the DS owns the title's typography (H800) and the body's
        // default config (B500 / contentSecondary / centered), so the description
        // is a bare Text. The settle emphasis and forfeit footnote keep explicit
        // typography/color overrides — the money math should still stand out.
        Text(
            text = if (subsidized) {
                stringResource(
                    Res.string.room_leave_bots_subsidized_body,
                    formatThousands(cashOutChips),
                )
            } else {
                stringResource(Res.string.room_leave_bots_body)
            },
        )
        if (showSettle) {
            Text(
                text = if (netSettleChips == 0L) {
                    stringResource(Res.string.room_leave_settle_even)
                } else {
                    stringResource(
                        Res.string.room_leave_settle_up,
                        netSettleChips.signedChips(),
                    )
                },
                typography = AppTheme.typography.Body.B600,
                color = if (netSettleChips < 0L) AppTheme.colors.danger else AppTheme.colors.content,
            )
            if (forfeitedThisHand > 0L) {
                Text(
                    text = stringResource(
                        Res.string.room_leave_forfeit_note,
                        formatThousands(forfeitedThisHand),
                    ),
                    typography = AppTheme.typography.Body.B400,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ButtonSecondary(
                onClick = onStay,
                modifier = Modifier.weight(1f),
            ) { Text(text = stringResource(Res.string.room_leave_bots_stay_button)) }
            ButtonPrimary(
                onClick = onLeave,
                modifier = Modifier.weight(1f),
            ) { Text(text = stringResource(Res.string.room_leave_bots_leave_button)) }
        }
    }
}

private fun Long.signedChips(): String =
    if (this > 0L) "+${formatThousands(this)}" else formatThousands(this)

@Preview
@Composable
private fun LeaveBotsConfirmDialogPreview() {
    PreviewContent {
        LeaveBotsConfirmDialog(
            onStay = {},
            onLeave = {},
        )
    }
}

@Preview
@Composable
private fun LeaveBotsConfirmDialogPreview_Subsidized() {
    PreviewContent {
        LeaveBotsConfirmDialog(
            onStay = {},
            onLeave = {},
            subsidized = true,
            cashOutChips = 9_180,
        )
    }
}

@Preview
@Composable
private fun LeaveBotsConfirmDialogPreview_SettleUp() {
    PreviewContent {
        LeaveBotsConfirmDialog(
            onStay = {},
            onLeave = {},
            subsidized = true,
            cashOutChips = 11_250,
            showSettle = true,
            netSettleChips = 1_250,
        )
    }
}

@Preview
@Composable
private fun LeaveBotsConfirmDialogPreview_SettleDownWithForfeit() {
    PreviewContent {
        LeaveBotsConfirmDialog(
            onStay = {},
            onLeave = {},
            subsidized = true,
            cashOutChips = 9_700,
            showSettle = true,
            netSettleChips = -300,
            forfeitedThisHand = 50,
        )
    }
}
