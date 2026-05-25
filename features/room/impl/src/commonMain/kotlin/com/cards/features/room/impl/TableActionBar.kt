package com.dangerfield.cards.features.room.impl

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
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.coroutines.delay
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
                                text = "Not enough chips to raise — try All In via ↑",
                                typography = AppTheme.typography.Body.B400,
                                color = AppTheme.colors.textSecondary,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ButtonTertiary(
                            onClick = {
                                onIntent(
                                    if (legal.canCheck) PlayerIntent.Check(seatIndex)
                                    else PlayerIntent.Call(seatIndex),
                                )
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            Text(
                                text = if (legal.canCheck) "Check"
                                else "Call ${legal.callAmount}",
                            )
                        }
                        ButtonTertiary(
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
                            onDisabledTap = { raiseHintVisible = true },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            Text(
                                text = when {
                                    !legal.canRaise -> if (legal.isOpenBet) "Bet" else "Raise"
                                    legal.isOpenBet -> "Bet ${legal.minRaiseTotal}"
                                    else -> "Raise ${legal.minRaiseTotal}"
                                },
                            )
                        }
                        IconButton(
                            icon = Icons.ArrowUp("More raise options"),
                            onClick = onExpandRaise,
                            backgroundColor = AppTheme.colors.surfaceSecondary,
                            size = IconButton.Size.Largest,
                        )
                    }
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
