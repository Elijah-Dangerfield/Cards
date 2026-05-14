package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The expanded action sheet — Fold / All In + a Bet|Raise editor with slider
 * and quick-pot chips. Shown when the user taps the ↑ button on the action bar.
 *
 * Lives separately from [PlayBotsScreen] because its preview matrix (open-bet
 * vs. raise, broke vs. flush, low/min/max raises) gets its own meaningful states
 * to iterate on.
 */
@Composable
internal fun RaiseSheet(
    legal: LegalActions,
    humanSeatIndex: Int,
    onDismiss: () -> Unit,
    onIntent: (PlayerIntent) -> Unit,
) {
    var raiseTotal by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(legal.minRaiseTotal.coerceAtMost(legal.maxRaiseTotal))
    }
    var raiseText by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(raiseTotal.toString())
    }
    // Slider / quick-pot taps snap both the text and the underlying total to
    // the clamped value — the user clicked a discrete value, the field should
    // reflect it.
    fun setRaiseTotal(v: Long) {
        val clamped = v.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
        raiseTotal = clamped
        raiseText = clamped.toString()
    }
    // Free-text typing must NOT clamp the visible text — otherwise a single
    // digit like "5" gets rewritten to the min on every keystroke and the
    // user can never compose a multi-digit number. Only the submitted total
    // (`raiseTotal`) and the slider position get clamped silently.
    fun onTypedRaise(typed: String) {
        val digitsOnly = typed.filter { it.isDigit() }.take(9)
        raiseText = digitsOnly
        val parsed = digitsOnly.toLongOrNull() ?: 0L
        raiseTotal = parsed.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "More options",
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.colors.onSurfacePrimary.color,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SheetPill(
                label = "Fold",
                modifier = Modifier.weight(1f),
                destructive = true,
            ) {
                onIntent(PlayerIntent.Fold(humanSeatIndex))
            }
            SheetPill(
                label = "All In ${legal.allInAmount}",
                modifier = Modifier.weight(1f),
            ) {
                onIntent(PlayerIntent.AllIn(humanSeatIndex))
            }
        }

        if (legal.canRaise) {
            val verb = if (legal.isOpenBet) "Bet" else "Raise to"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = verb,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
                RaiseField(
                    text = raiseText,
                    onTextChange = ::onTypedRaise,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "max ${legal.maxRaiseTotal}",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }
            Slider(
                value = raiseTotal.toFloat(),
                onValueChange = { setRaiseTotal(it.toLong()) },
                valueRange = legal.minRaiseTotal.toFloat()..legal.maxRaiseTotal.toFloat(),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val pot = legal.potIfYouCall
                QuickPotPill(label = "½ pot", modifier = Modifier.weight(1f)) {
                    setRaiseTotal(pot / 2)
                }
                QuickPotPill(label = "¾ pot", modifier = Modifier.weight(1f)) {
                    setRaiseTotal((pot * 3) / 4)
                }
                QuickPotPill(label = "Pot", modifier = Modifier.weight(1f)) {
                    setRaiseTotal(pot)
                }
            }
            ConfirmPill(label = "$verb $raiseTotal") {
                onIntent(
                    if (legal.isOpenBet) {
                        PlayerIntent.Bet(humanSeatIndex, raiseTotal)
                    } else {
                        PlayerIntent.Raise(humanSeatIndex, raiseTotal)
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetPill(
    label: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    // `destructive` taps the design system's `danger` token (Red600) for the
    // Fold action — visually distinct so the user doesn't fat-finger it on the
    // way to All In, and consistent with how the rest of the app signals
    // irreversible actions.
    val bg = if (destructive) AppTheme.colors.danger else AppTheme.colors.surfaceSecondary
    val fg = if (destructive) AppTheme.colors.onAccentPrimary else AppTheme.colors.onSurfacePrimary
    Box(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(bg.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B600,
            color = fg,
        )
    }
}

@Composable
private fun ConfirmPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(AppTheme.colors.accentPrimary.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Composable
private fun QuickPotPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfacePrimary,
        )
    }
}

@Composable
private fun RaiseField(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            typographyToken = AppTheme.typography.Heading.H600,
        )
    }
}

// ----- Previews -------------------------------------------------------------

private fun previewLegal(
    canRaise: Boolean = true,
    isOpenBet: Boolean = false,
    minRaiseTotal: Long = 40,
    maxRaiseTotal: Long = 980,
    potIfYouCall: Long = 50,
    allInAmount: Long = 980,
): LegalActions = LegalActions(
    canCheck = !isOpenBet,
    canCall = !isOpenBet,
    callAmount = if (isOpenBet) 0 else 20,
    canRaise = canRaise,
    isOpenBet = isOpenBet,
    minRaiseTotal = minRaiseTotal,
    maxRaiseTotal = maxRaiseTotal,
    canAllIn = true,
    allInAmount = allInAmount,
    potIfYouCall = potIfYouCall,
)

@Preview
@Composable
private fun RaiseSheetPreview_FacingABet() {
    PreviewContent {
        RaiseSheet(
            legal = previewLegal(isOpenBet = false),
            humanSeatIndex = 0,
            onDismiss = {},
            onIntent = {},
        )
    }
}

@Preview
@Composable
private fun RaiseSheetPreview_OpenBet() {
    PreviewContent {
        RaiseSheet(
            legal = previewLegal(isOpenBet = true, minRaiseTotal = 20, maxRaiseTotal = 1000, potIfYouCall = 30),
            humanSeatIndex = 0,
            onDismiss = {},
            onIntent = {},
        )
    }
}

@Preview
@Composable
private fun RaiseSheetPreview_CannotRaise() {
    PreviewContent {
        RaiseSheet(
            legal = previewLegal(canRaise = false, maxRaiseTotal = 15, allInAmount = 15),
            humanSeatIndex = 0,
            onDismiss = {},
            onIntent = {},
        )
    }
}
