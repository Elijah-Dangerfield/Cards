package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.private_create_blinds_caption
import cards.libraries.resources.generated.resources.private_create_buyin_label
import cards.libraries.resources.generated.resources.private_create_card_back_label
import cards.libraries.resources.generated.resources.private_create_felt_label
import cards.libraries.resources.generated.resources.private_create_max_players_label
import cards.libraries.resources.generated.resources.standing_table_create_at
import cards.libraries.resources.generated.resources.standing_table_create_every
import cards.libraries.resources.generated.resources.standing_table_create_name
import cards.libraries.resources.generated.resources.standing_table_create_placeholder
import cards.libraries.resources.generated.resources.standing_table_create_submit
import cards.libraries.resources.generated.resources.standing_table_create_submitting
import cards.libraries.resources.generated.resources.standing_table_create_title
import cards.libraries.resources.generated.resources.standing_table_create_zone
import cards.libraries.resources.generated.resources.standing_table_edit_submit
import cards.libraries.resources.generated.resources.standing_table_edit_title
import cards.libraries.resources.generated.resources.standing_table_error_invalid
import cards.libraries.resources.generated.resources.standing_table_error_limit
import cards.libraries.resources.generated.resources.standing_table_error_network
import cards.libraries.resources.generated.resources.standing_table_error_owner
import cards.libraries.resources.generated.resources.standing_table_error_retry
import cards.libraries.resources.generated.resources.standing_table_field_duration
import cards.libraries.resources.generated.resources.standing_table_field_time
import cards.libraries.resources.generated.resources.standing_table_field_weekday
import cards.libraries.resources.generated.resources.standing_table_field_zone
import com.dangerfield.cards.features.lobby.impl.create.BuyInRow
import com.dangerfield.cards.features.lobby.impl.create.CosmeticPickerRow
import com.dangerfield.cards.features.lobby.impl.create.MaxPlayersRow
import com.dangerfield.cards.features.lobby.impl.create.RuleDivider
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.TimeOfDayPicker
import com.dangerfield.cards.libraries.ui.components.room.WeekdayPicker
import com.dangerfield.cards.libraries.ui.components.room.weekdayNameResource
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StandingTableCreateScreen(
    state: StandingTableCreateState,
    onAction: (StandingTableCreateAction) -> Unit,
    onBack: () -> Unit,
    chipBalance: Long? = null,
    felts: List<CosmeticChoice> = emptyList(),
    cardBacks: List<CosmeticChoice> = emptyList(),
) {
    val enabled = !state.isSubmitting && !state.isLoading && state.isOwner
    val defaultName = stringResource(
        Res.string.standing_table_create_placeholder,
        stringResource(weekdayNameResource(state.weekday)),
    )
    val settings = RoomSettings.forBuyIn(state.buyIn, state.maxSeats)
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(
                    if (state.editing) Res.string.standing_table_edit_title
                    else Res.string.standing_table_create_title,
                ),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().screenContentPadding(paddingValues = padding)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimension.D400),
            ) {
                Text(
                    stringResource(Res.string.standing_table_create_name),
                    typography = AppTheme.typography.Label.L500,
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onAction(StandingTableCreateAction.NameChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    isError = (state.error as? StandingTableFormError.Invalid)?.field == "name",
                    placeholder = { Text(defaultName) },
                )
                Text(
                    stringResource(Res.string.standing_table_create_every),
                    typography = AppTheme.typography.Label.L500,
                )
                WeekdayPicker(
                    selected = state.weekday,
                    onSelect = { onAction(StandingTableCreateAction.WeekdayChanged(it)) },
                    enabled = enabled,
                )
                Text(
                    stringResource(Res.string.standing_table_create_at),
                    typography = AppTheme.typography.Label.L500,
                )
                TimeOfDayPicker(
                    time = state.time,
                    onChange = { onAction(StandingTableCreateAction.TimeChanged(it)) },
                    enabled = enabled,
                )
                Text(
                    text = stringResource(
                        Res.string.standing_table_create_zone,
                        state.timeZone.id.substringAfterLast('/').replace('_', ' '),
                    ),
                    typography = AppTheme.typography.Caption.C300,
                    color = AppTheme.colors.contentSecondary,
                )
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(Radii.Card.shape)
                        .background(AppTheme.colors.surface.color)
                        .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape),
                ) {
                    BuyInRow(
                        buyIn = state.buyIn,
                        blindsCaption = stringResource(
                            Res.string.private_create_blinds_caption,
                            settings.smallBlind,
                            settings.bigBlind,
                        ),
                        maxBuyIn = (chipBalance ?: RoomSettings.DEFAULT_BUY_IN).coerceAtLeast(state.buyIn),
                        onBuyInChange = { if (enabled) onAction(StandingTableCreateAction.BuyInChanged(it)) },
                    )
                    RuleDivider()
                    MaxPlayersRow(
                        value = state.maxSeats,
                        onDecrement = { if (enabled) onAction(StandingTableCreateAction.SeatsChanged(state.maxSeats - 1)) },
                        onIncrement = { if (enabled) onAction(StandingTableCreateAction.SeatsChanged(state.maxSeats + 1)) },
                    )
                    if (!state.editing && felts.isNotEmpty()) {
                        RuleDivider()
                        CosmeticPickerRow(
                            label = stringResource(Res.string.private_create_felt_label),
                            choices = felts,
                            selectedProductId = state.feltProductId,
                            onSelect = { if (enabled) onAction(StandingTableCreateAction.FeltChanged(it)) },
                        )
                    }
                    if (!state.editing && cardBacks.isNotEmpty()) {
                        RuleDivider()
                        CosmeticPickerRow(
                            label = stringResource(Res.string.private_create_card_back_label),
                            choices = cardBacks,
                            selectedProductId = state.cardBackProductId,
                            onSelect = { if (enabled) onAction(StandingTableCreateAction.CardBackChanged(it)) },
                        )
                    }
                }
                state.error?.let { error ->
                    val message = when (error) {
                        StandingTableFormError.TooMany -> stringResource(Res.string.standing_table_error_limit)
                        is StandingTableFormError.Invalid -> stringResource(
                            Res.string.standing_table_error_invalid,
                            standingTableFieldLabel(error.field),
                        )
                        StandingTableFormError.Network -> stringResource(Res.string.standing_table_error_network)
                        StandingTableFormError.NotOwner -> stringResource(Res.string.standing_table_error_owner)
                    }
                    Text(message, color = AppTheme.colors.danger, typography = AppTheme.typography.Body.B400)
                    if (state.editing && !state.isOwner && error == StandingTableFormError.Network) {
                        ButtonSecondary(onClick = { onAction(StandingTableCreateAction.Load) }) {
                            Text(stringResource(Res.string.standing_table_error_retry))
                        }
                    }
                }
                Spacer(Modifier.height(Dimension.D400))
            }
            Spacer(Modifier.height(Dimension.D400))
            ButtonPrimary(
                onClick = { onAction(StandingTableCreateAction.Submit(defaultName)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when {
                            state.isSubmitting -> Res.string.standing_table_create_submitting
                            state.editing -> Res.string.standing_table_edit_submit
                            else -> Res.string.standing_table_create_submit
                        },
                    ),
                )
            }
            Spacer(Modifier.height(Dimension.D500))
        }
    }
}

@Composable
private fun standingTableFieldLabel(field: String): String = stringResource(
    when (field) {
        "buyIn" -> Res.string.private_create_buyin_label
        "maxSeats" -> Res.string.private_create_max_players_label
        "weekday" -> Res.string.standing_table_field_weekday
        "localTime" -> Res.string.standing_table_field_time
        "timeZone" -> Res.string.standing_table_field_zone
        "durationMinutes" -> Res.string.standing_table_field_duration
        else -> Res.string.standing_table_create_name
    },
)

@Preview
@Composable
private fun StandingTableCreateScreenPreview_Empty() {
    PreviewContent { StandingTableCreateScreen(StandingTableCreateState(), {}, {}) }
}

@Preview
@Composable
private fun StandingTableCreateScreenPreview_Filled() {
    PreviewContent {
        StandingTableCreateScreen(
            state = StandingTableCreateState(name = "Thursday Night Poker"),
            onAction = {},
            onBack = {},
            chipBalance = 25000,
            felts = listOf(CosmeticChoice("felt_default", "🟢")),
            cardBacks = listOf(CosmeticChoice("cardback_default", "🂠")),
        )
    }
}

@Preview
@Composable
private fun StandingTableCreateScreenPreview_Submitting() {
    PreviewContent {
        StandingTableCreateScreen(StandingTableCreateState(name = "Thursday Night Poker", isSubmitting = true), {}, {})
    }
}

@Preview
@Composable
private fun StandingTableCreateScreenPreview_Error() {
    PreviewContent {
        StandingTableCreateScreen(StandingTableCreateState(name = "Friday Friends", error = StandingTableFormError.TooMany), {}, {})
    }
}

@Preview
@Composable
private fun StandingTableCreateScreenPreview_Edit() {
    PreviewContent {
        StandingTableCreateScreen(StandingTableCreateState(name = "Thursday Night Poker", editing = true), {}, {})
    }
}
