package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.LegalActions
import com.dangerfield.cards.features.room.impl.TableUiState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_quick_action_bet_button_with_amount
import cards.libraries.resources.generated.resources.room_quick_action_bet_verb
import cards.libraries.resources.generated.resources.room_quick_action_call_button
import cards.libraries.resources.generated.resources.room_quick_action_check_button
import cards.libraries.resources.generated.resources.room_preaction_armed_a11y
import cards.libraries.resources.generated.resources.room_preaction_armed_fold_label
import cards.libraries.resources.generated.resources.room_preaction_fold_label
import cards.libraries.resources.generated.resources.room_quick_action_more_options_a11y
import cards.libraries.resources.generated.resources.room_quick_action_raise_button_with_amount
import cards.libraries.resources.generated.resources.room_quick_action_raise_hint_no_chips
import cards.libraries.resources.generated.resources.room_quick_action_raise_verb
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.poker.LocalFeltAccentSurface
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun QuickActionBar(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
    onExpandRaise: () -> Unit,
) {
    val currentLegal = table.humanLegalActions
    val humanSeat = table.seats.firstOrNull { it.isHuman }
    val canShow = table.isHumanTurn && currentLegal != null && humanSeat != null

    // Cache the last live LegalActions so the row keeps rendering its previous
    // labels through the exit animation. Without this it'd flash blank as the
    // turn moves to a bot and `currentLegal` becomes null.
    var lastLegal by remember { mutableStateOf<LegalActions?>(null) }
    var lastSeatIndex by remember { mutableStateOf<Int?>(null) }
    if (currentLegal != null && humanSeat != null) {
        lastLegal = currentLegal
        lastSeatIndex = humanSeat.index
    }

    var raiseHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(raiseHintVisible) {
        if (raiseHintVisible) {
            delay(2400)
            raiseHintVisible = false
        }
    }

    // No fixed-height wrapper here — the bar's slot collapses when actions
    // aren't visible so the player row above slides DOWN to fill the freed
    // space (see ActiveTable: PlayerArea + this bar share a Column).
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = canShow,
            // `expand/shrinkVertically` animate the LAYOUT slot's height in
            // lockstep with the slide — without them the slot snaps from
            // content-size to 0 at the end of the slide-out animation,
            // which makes the player row above appear to "jump" down at
            // the very end instead of sliding down smoothly with the bar.
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(260)) +
                expandVertically(animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) +
                shrinkVertically(animationSpec = tween(220)) +
                fadeOut(animationSpec = tween(140)),
        ) {
            val legal = lastLegal
            val seatIndex = lastSeatIndex
            if (legal != null && seatIndex != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().height(Dimension.D800), contentAlignment = Alignment.Center) {
                        if (raiseHintVisible) {
                            Text(
                                text = stringResource(Res.string.room_quick_action_raise_hint_no_chips),
                                typography = AppTheme.typography.Body.B400,
                                color = AppTheme.colors.contentSecondary,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ButtonSecondary(
                            onClick = {
                                onIntent(
                                    if (legal.canCheck) PlayerIntent.Check(seatIndex)
                                    else PlayerIntent.Call(seatIndex),
                                )
                            },

                            flat = true,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            Text(
                                text = if (legal.canCheck) {
                                    stringResource(Res.string.room_quick_action_check_button)
                                } else {
                                    stringResource(
                                        Res.string.room_quick_action_call_button,
                                        formatCompactChips(legal.callAmount),
                                    )
                                },
                            )
                        }
                        ButtonPrimary(
                            onClick = {
                                onIntent(
                                    if (legal.isOpenBet) {
                                        PlayerIntent.Bet(seatIndex, legal.minRaiseTotal)
                                    } else {
                                        PlayerIntent.Raise(seatIndex, legal.minRaiseTotal)
                                    },
                                )
                            },
                            enabled = legal.canRaise,
                            flat = true,
                            onDisabledTap = { raiseHintVisible = true },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            Text(
                                text = when {
                                    !legal.canRaise -> if (legal.isOpenBet) {
                                        stringResource(Res.string.room_quick_action_bet_verb)
                                    } else {
                                        stringResource(Res.string.room_quick_action_raise_verb)
                                    }
                                    legal.isOpenBet -> stringResource(
                                        Res.string.room_quick_action_bet_button_with_amount,
                                        formatCompactChips(legal.minRaiseTotal),
                                    )
                                    else -> stringResource(
                                        Res.string.room_quick_action_raise_button_with_amount,
                                        formatCompactChips(legal.minRaiseTotal),
                                    )
                                },
                            )
                        }
                        // Per-felt accent — falls through to the standard
                        // secondary surface outside the play screen (or
                        // when felt = Default). Keeps the icon-button
                        // chrome legible across every felt choice.
                        val feltAccent = LocalFeltAccentSurface.current
                        IconButton(
                            icon = Icons.ArrowUp(stringResource(Res.string.room_quick_action_more_options_a11y)),
                            onClick = onExpandRaise,
                            backgroundColor = if (feltAccent != null) null else AppTheme.colors.surfaceRaised,
                            backgroundOverride = feltAccent,
                            size = IconButton.Size.Largest,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The single pre-fold control shown in the action slot while it's *not* the
 * human's turn (GAME-30). Tapping it arms a fold that fires automatically the
 * moment the turn arrives — always a fold, whatever the action did while waiting.
 * Armed is a distinct, cancelable state: tap again (any time before it fires) to
 * back out. Collapses (handing the slot to [QuickActionBar]) the instant the turn
 * lands. Replaces the old conditional "Check/Fold" + "Check" toggle pair, which
 * read as two near-identical buttons no casual player could tell apart.
 */
@Composable
internal fun PreFoldBar(
    table: TableUiState.Active,
    armed: Boolean,
    onArmChange: (Boolean) -> Unit,
) {
    val human = table.seats.firstOrNull { it.isHuman }
    val canArm = !table.isHumanTurn &&
        table.handResult == null &&
        human != null &&
        human.participation == HandParticipation.InHand

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = canArm,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(260)) +
                expandVertically(animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) +
                shrinkVertically(animationSpec = tween(220)) +
                fadeOut(animationSpec = tween(140)),
        ) {
            // Armed reads as a filled primary with a check + "tap to cancel" copy;
            // unarmed is the plain outlined secondary. Swapping the whole button
            // type keeps the selected state on DS tokens, not a hand-tuned highlight.
            if (armed) {
                ButtonPrimary(
                    onClick = { onArmChange(false) },
                    icon = Icons.Check(stringResource(Res.string.room_preaction_armed_a11y)),
                    flat = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(Res.string.room_preaction_armed_fold_label))
                }
            } else {
                ButtonSecondary(
                    onClick = { onArmChange(true) },
                    flat = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(Res.string.room_preaction_fold_label))
                }
            }
        }
    }
}

@Preview
@Composable
private fun QuickActionBarPreview_CallOrRaise() {
    PreviewContent {
        QuickActionBar(
            table = PreviewSamples.activeTable(
                legalActions = PreviewSamples.legalActions(callAmount = 20, minRaiseTotal = 40),
            ),
            onIntent = {},
            onExpandRaise = {},
        )
    }
}

@Preview
@Composable
private fun QuickActionBarPreview_CheckOrBet() {
    PreviewContent {
        QuickActionBar(
            table = PreviewSamples.activeTable(
                legalActions = PreviewSamples.legalActions(
                    canCheck = true,
                    canCall = false,
                    callAmount = 0,
                    isOpenBet = true,
                    minRaiseTotal = 20,
                ),
            ),
            onIntent = {},
            onExpandRaise = {},
        )
    }
}

@Preview
@Composable
private fun QuickActionBarPreview_RaiseBlocked() {
    PreviewContent {
        QuickActionBar(
            table = PreviewSamples.activeTable(
                seats = PreviewSamples.defaultSeats().map {
                    if (it.isHuman) it.copy(stack = 15) else it
                },
                legalActions = PreviewSamples.legalActions(
                    canRaise = false,
                    callAmount = 20,
                    minRaiseTotal = 40,
                    maxRaiseTotal = 15,
                ),
            ),
            onIntent = {},
            onExpandRaise = {},
        )
    }
}

@Preview
@Composable
private fun PreFoldBarPreview_Unarmed() {
    PreviewContent {
        PreFoldBar(
            table = PreviewSamples.activeTable(actingSeatIndex = 1),
            armed = false,
            onArmChange = {},
        )
    }
}

@Preview
@Composable
private fun PreFoldBarPreview_Armed() {
    PreviewContent {
        PreFoldBar(
            table = PreviewSamples.activeTable(actingSeatIndex = 1),
            armed = true,
            onArmChange = {},
        )
    }
}
