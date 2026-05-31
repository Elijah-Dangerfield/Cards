package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_action_all_in
import cards.libraries.resources.generated.resources.room_action_confirm_bet
import cards.libraries.resources.generated.resources.room_action_confirm_raise_to
import cards.libraries.resources.generated.resources.room_action_fold
import cards.libraries.resources.generated.resources.room_action_max_hint
import cards.libraries.resources.generated.resources.room_action_preset_half_pot
import cards.libraries.resources.generated.resources.room_action_preset_min
import cards.libraries.resources.generated.resources.room_action_preset_pot
import cards.libraries.resources.generated.resources.room_action_preset_three_quarter_pot
import cards.libraries.resources.generated.resources.room_action_stepper_decrement
import cards.libraries.resources.generated.resources.room_action_stepper_increment
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Expanded action sheet — Fold / All In at the top, a row of preset
 * bet-size pills, then a `–  [amount]  +` stepper where the amount is
 * itself a tappable text field. Tap the number → keyboard opens → type
 * anything. – / + nudge by min-raise increments. No Custom toggle: the
 * field is always editable, the presets are just shortcuts.
 */
@Composable
internal fun PlayerActionSheet(
    legal: LegalActions,
    humanSeatIndex: Int,
    onIntent: (PlayerIntent) -> Unit,
) {
    // Only build presets when a raise is actually legal. When the seat is too
    // short to make a full min-raise, `canRaise` is false and `maxRaiseTotal`
    // sits *below* `minRaiseTotal` — computing presets in that state would feed
    // an inverted range into `coerceIn` and crash. The raise UI below is gated
    // on `canRaise` anyway, so there is nothing to show.
    val presets = remember(legal) { if (legal.canRaise) BetPresets.from(legal) else emptyList() }
    var selectedPreset by remember(legal) { mutableStateOf(presets.firstOrNull()) }
    var raiseTotal by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(selectedPreset?.amount ?: legal.minRaiseTotal)
    }
    var raiseText by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(raiseTotal.toString())
    }

    fun clampAndSet(v: Long) {
        val clamped = v.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
        raiseTotal = clamped
        raiseText = clamped.toString()
    }

    fun selectPreset(preset: BetPreset) {
        selectedPreset = preset
        clampAndSet(preset.amount)
    }

    fun onTypedRaise(typed: String) {
        // Free-text typing must NOT clamp the visible text — otherwise a
        // single digit like "5" gets rewritten to the min on every keystroke
        // and the user can never compose a multi-digit number. Only the
        // submitted total gets clamped silently.
        val digitsOnly = typed.filter { it.isDigit() }.take(9)
        raiseText = digitsOnly
        val parsed = digitsOnly.toLongOrNull() ?: 0L
        raiseTotal = parsed.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
        // Free typing means the user is no longer "on" a preset.
        selectedPreset = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            BigActionPill(
                label = stringResource(Res.string.room_action_fold),
                modifier = Modifier.weight(1f),
                style = PillStyle.Destructive,
            ) {
                onIntent(PlayerIntent.Fold(humanSeatIndex))
            }
            BigActionPill(
                label = stringResource(Res.string.room_action_all_in),
                sublabel = formatCompactChips(legal.allInAmount),
                modifier = Modifier.weight(1f),
                style = PillStyle.Neutral,
            ) {
                onIntent(PlayerIntent.AllIn(humanSeatIndex))
            }
        }

        if (legal.canRaise) {
            BetPresetGrid(
                presets = presets,
                selected = selectedPreset,
                onSelect = { selectPreset(it) },
            )

            StepperRow(
                amount = raiseTotal,
                amountText = raiseText,
                onAmountTextChange = ::onTypedRaise,
                onStep = { delta -> clampAndSet(raiseTotal + delta) },
                step = legal.minRaiseTotal.coerceAtLeast(1L),
                minAmount = legal.minRaiseTotal,
                maxAmount = legal.maxRaiseTotal,
            )
            Text(
                text = stringResource(Res.string.room_action_max_hint, formatCompactChips(legal.maxRaiseTotal)),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            val confirmLabel = if (legal.isOpenBet) {
                stringResource(Res.string.room_action_confirm_bet, raiseTotal.toString())
            } else {
                stringResource(Res.string.room_action_confirm_raise_to, raiseTotal.toString())
            }
            ConfirmPill(label = confirmLabel) {
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

/**
 * Catalog of bet-size presets for the current legal state. Built once per
 * `LegalActions` since min/max/pot don't shift mid-decision.
 *
 * `½ Pot` and `¾ Pot` are dropped when the pot is small enough that they'd
 * round below the legal min — collapsing those into a single Min keeps the
 * row clean instead of showing three pills that all submit the same value.
 */
internal data class BetPreset(val labelResource: StringResource, val amount: Long)

internal object BetPresets {
    fun from(legal: LegalActions): List<BetPreset> {
        val min = legal.minRaiseTotal
        val max = legal.maxRaiseTotal
        // A seat too short to make a full min-raise has `max < min` (an empty
        // range). `coerceIn(min, max)` throws on that, so bail before clamping.
        // Callers should already gate on `canRaise`; this is the backstop.
        if (max < min) return emptyList()
        val pot = legal.potIfYouCall
        val raw = listOfNotNull(
            BetPreset(Res.string.room_action_preset_min, min),
            if (pot >= min * 2) BetPreset(Res.string.room_action_preset_half_pot, (pot / 2).coerceIn(min, max)) else null,
            if (pot >= min) BetPreset(Res.string.room_action_preset_three_quarter_pot, ((pot * 3) / 4).coerceIn(min, max)) else null,
            BetPreset(Res.string.room_action_preset_pot, pot.coerceIn(min, max)),
        )
        return raw.distinctBy { it.amount }
    }
}

@Composable
private fun BetPresetGrid(
    presets: List<BetPreset>,
    selected: BetPreset?,
    onSelect: (BetPreset) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            PresetPill(
                label = stringResource(preset.labelResource),
                selected = selected?.amount == preset.amount,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(preset) },
            )
        }
    }
}

@Composable
private fun PresetPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) AppTheme.colors.accentPrimary.color
    else AppTheme.colors.surfaceRaised.color
    val fg = if (selected) AppTheme.colors.onAccentPrimary else AppTheme.colors.content
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(Radii.R1000.shape)
            .background(bg)
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
private fun StepperRow(
    amount: Long,
    amountText: String,
    onAmountTextChange: (String) -> Unit,
    onStep: (delta: Long) -> Unit,
    step: Long,
    minAmount: Long,
    maxAmount: Long,
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(
            label = stringResource(Res.string.room_action_stepper_decrement),
            enabled = amount > minAmount,
            onClick = { onStep(-step) },
        )
        // The number pill IS the text field. Tapping it (text or pill
        // background) requests focus → keyboard opens → user types. – / +
        // continue to work; typing just deselects any preset.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .clip(Radii.Button.shape)
                .background(AppTheme.colors.surfaceRaised.color)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    focusRequester.requestFocus()
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = amountText,
                onValueChange = onAmountTextChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                typographyToken = AppTheme.typography.Heading.H700,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        StepperButton(
            label = stringResource(Res.string.room_action_stepper_increment),
            enabled = amount < maxAmount,
            onClick = { onStep(step) },
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (enabled) AppTheme.colors.surfaceRaised.color
    else AppTheme.colors.surfaceDisabled.color
    val fg = if (enabled) AppTheme.colors.content else AppTheme.colors.contentDisabled
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(Radii.IconButton.shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Heading.H700,
            color = fg,
        )
    }
}

private enum class PillStyle { Destructive, Neutral }

@Composable
private fun BigActionPill(
    label: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    style: PillStyle = PillStyle.Neutral,
    onClick: () -> Unit,
) {
    // `Destructive` taps the design system's `danger` token (Red600) for the
    // Fold action — visually distinct so the user doesn't fat-finger it on
    // the way to All In, and consistent with how the rest of the app signals
    // irreversible actions.
    val bg = when (style) {
        PillStyle.Destructive -> AppTheme.colors.danger
        PillStyle.Neutral -> AppTheme.colors.surfaceRaised
    }
    val fg = when (style) {
        PillStyle.Destructive -> AppTheme.colors.onAccentPrimary
        PillStyle.Neutral -> AppTheme.colors.content
    }
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(Radii.Button.shape)
            .background(bg.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                typography = AppTheme.typography.Heading.H600,
                color = fg,
            )
            if (sublabel != null) {
                VerticalSpacerD200()
                Text(
                    text = sublabel,
                    typography = AppTheme.typography.Body.B400,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun ConfirmPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(Radii.Button.shape)
            .background(AppTheme.colors.poker.chipGold.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.background,
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
private fun PlayerActionSheetPreview_FacingABet() {
    PreviewContent {
        PlayerActionSheet(
            legal = previewLegal(isOpenBet = false),
            humanSeatIndex = 0,
            onIntent = {},
        )
    }
}

@Preview
@Composable
private fun PlayerActionSheetPreview_OpenBet() {
    PreviewContent {
        PlayerActionSheet(
            legal = previewLegal(isOpenBet = true, minRaiseTotal = 20, maxRaiseTotal = 1000, potIfYouCall = 30),
            humanSeatIndex = 0,
            onIntent = {},
        )
    }
}

@Preview
@Composable
private fun PlayerActionSheetPreview_CannotRaise() {
    PreviewContent {
        PlayerActionSheet(
            legal = previewLegal(canRaise = false, maxRaiseTotal = 15, allInAmount = 15),
            humanSeatIndex = 0,
            onIntent = {},
        )
    }
}
