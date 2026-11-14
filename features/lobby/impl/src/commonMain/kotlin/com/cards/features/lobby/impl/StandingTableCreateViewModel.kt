package com.dangerfield.cards.features.lobby.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.rooms.CreateStandingTableOutcome
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableDraft
import com.dangerfield.cards.libraries.rooms.StandingTableRepository
import com.dangerfield.cards.libraries.rooms.StandingTablesEnabled
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock

@Inject
class StandingTableCreateViewModel(
    @Assisted private val editCode: String?,
    private val repository: StandingTableRepository,
    clock: Clock,
    private val enabled: StandingTablesEnabled,
) : SEAViewModel<StandingTableCreateState, StandingTableCreateEvent, StandingTableCreateAction>(
    initialStateArg = StandingTableCreateState(
        weekday = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek,
        editing = editCode != null,
        isLoading = editCode != null,
        isOwner = editCode == null,
    ),
) {
    private var completed = false

    init {
        if (editCode != null && enabled()) takeAction(StandingTableCreateAction.Load)
    }

    override suspend fun handleAction(action: StandingTableCreateAction) {
        if (!enabled()) return
        when (action) {
            StandingTableCreateAction.Load -> action.load()
            is StandingTableCreateAction.NameChanged -> action.updateState {
                it.copy(name = action.value.take(32), error = null)
            }
            is StandingTableCreateAction.WeekdayChanged -> action.updateState {
                it.copy(weekday = action.value, error = null)
            }
            is StandingTableCreateAction.TimeChanged -> action.updateState {
                it.copy(time = action.value, error = null)
            }
            is StandingTableCreateAction.BuyInChanged -> action.updateState {
                it.copy(buyIn = action.value, error = null)
            }
            is StandingTableCreateAction.SeatsChanged -> action.updateState {
                it.copy(maxSeats = action.value.coerceIn(RoomSettings.MIN_SEATS, RoomSettings.MAX_SEATS))
            }
            is StandingTableCreateAction.FeltChanged -> action.updateState {
                it.copy(feltProductId = action.value)
            }
            is StandingTableCreateAction.CardBackChanged -> action.updateState {
                it.copy(cardBackProductId = action.value)
            }
            is StandingTableCreateAction.Submit -> action.submit()
            is StandingTableCreateAction.CreateFinished -> action.applyCreateResult()
            is StandingTableCreateAction.UpdateFinished -> action.result.fold(
                onSuccess = {
                    completed = true
                    action.updateState { it.copy(isSubmitting = false) }
                    sendEvent(StandingTableCreateEvent.Saved)
                },
                onFailure = {
                    action.updateState { it.copy(isSubmitting = false, error = StandingTableFormError.Network) }
                },
            )
        }
    }

    private suspend fun StandingTableCreateAction.load() {
        val code = editCode ?: return
        updateState { it.copy(isLoading = true, isOwner = false, error = null) }
        repository.get(code).fold(
            onSuccess = { table ->
                updateState {
                    it.copy(
                        name = table.name,
                        weekday = table.weekday,
                        time = table.localTime,
                        timeZone = table.timeZone,
                        buyIn = table.buyIn,
                        maxSeats = table.maxSeats,
                        durationMinutes = table.durationMinutes,
                        feltProductId = table.feltProductId,
                        cardBackProductId = table.cardBackProductId,
                        isLoading = false,
                        isOwner = table.isOwner,
                        error = if (table.isOwner) null else StandingTableFormError.NotOwner,
                    )
                }
            },
            onFailure = {
                updateState { it.copy(isLoading = false, error = StandingTableFormError.Network) }
            },
        )
    }

    private suspend fun StandingTableCreateAction.Submit.submit() {
        val current = state
        if (completed || current.isSubmitting || current.isLoading || !current.isOwner) return
        updateState { it.copy(isSubmitting = true, error = null) }
        val draft = StandingTableDraft(
            name = current.name.trim().ifBlank { defaultName },
            buyIn = current.buyIn,
            maxSeats = current.maxSeats,
            weekday = current.weekday,
            localTime = current.time,
            timeZone = current.timeZone,
            durationMinutes = current.durationMinutes,
            feltProductId = current.feltProductId,
            cardBackProductId = current.cardBackProductId,
        )
        viewModelScope.launch {
            if (editCode == null) {
                takeAction(StandingTableCreateAction.CreateFinished(repository.create(draft), draft))
            } else {
                takeAction(StandingTableCreateAction.UpdateFinished(repository.update(editCode, draft)))
            }
        }
    }

    private suspend fun StandingTableCreateAction.CreateFinished.applyCreateResult() {
        when (val outcome = result) {
            is CreateStandingTableOutcome.Created -> {
                completed = true
                KLog.logEvent(
                    "standing_table.created",
                    "weekday" to draft.weekday.ordinal + 1,
                    "hour" to draft.localTime.hour,
                    "max_seats" to draft.maxSeats,
                    "buy_in" to draft.buyIn,
                )
                updateState { it.copy(isSubmitting = false) }
                sendEvent(StandingTableCreateEvent.Created(outcome.table.code))
            }
            CreateStandingTableOutcome.TooMany -> fail(StandingTableFormError.TooMany, "too_many")
            is CreateStandingTableOutcome.Invalid -> fail(StandingTableFormError.Invalid(outcome.field), "invalid")
            CreateStandingTableOutcome.NetworkError -> fail(StandingTableFormError.Network, "network")
        }
    }

    private suspend fun StandingTableCreateAction.fail(error: StandingTableFormError, reason: String) {
        KLog.logEvent("standing_table.create_failed", "reason" to reason)
        updateState { it.copy(isSubmitting = false, error = error) }
    }
}

data class StandingTableCreateState(
    val name: String = "",
    val weekday: DayOfWeek = DayOfWeek.THURSDAY,
    val time: LocalTime = LocalTime(20, 0),
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    val buyIn: Long = RoomSettings.DEFAULT_HOST_BUY_IN,
    val maxSeats: Int = 6,
    val durationMinutes: Int = 120,
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
    val isSubmitting: Boolean = false,
    val isLoading: Boolean = false,
    val editing: Boolean = false,
    val isOwner: Boolean = true,
    val error: StandingTableFormError? = null,
)

sealed interface StandingTableFormError {
    data object TooMany : StandingTableFormError
    data class Invalid(val field: String) : StandingTableFormError
    data object Network : StandingTableFormError
    data object NotOwner : StandingTableFormError
}

sealed interface StandingTableCreateAction {
    data object Load : StandingTableCreateAction
    data class NameChanged(val value: String) : StandingTableCreateAction
    data class WeekdayChanged(val value: DayOfWeek) : StandingTableCreateAction
    data class TimeChanged(val value: LocalTime) : StandingTableCreateAction
    data class BuyInChanged(val value: Long) : StandingTableCreateAction
    data class SeatsChanged(val value: Int) : StandingTableCreateAction
    data class FeltChanged(val value: String) : StandingTableCreateAction
    data class CardBackChanged(val value: String) : StandingTableCreateAction
    data class Submit(val defaultName: String) : StandingTableCreateAction
    data class CreateFinished(val result: CreateStandingTableOutcome, val draft: StandingTableDraft) : StandingTableCreateAction
    data class UpdateFinished(val result: Catching<StandingTable>) : StandingTableCreateAction
}

sealed interface StandingTableCreateEvent {
    data class Created(val code: String) : StandingTableCreateEvent
    data object Saved : StandingTableCreateEvent
}
