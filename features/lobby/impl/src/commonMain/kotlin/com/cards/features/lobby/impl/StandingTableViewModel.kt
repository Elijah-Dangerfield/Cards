package com.dangerfield.cards.features.lobby.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableRepository
import com.dangerfield.cards.libraries.rooms.StandingTablesEnabled
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

@Inject
class StandingTableViewModel(
    @Assisted private val code: String,
    @Assisted justCreated: Boolean,
    private val repository: StandingTableRepository,
    private val enabled: StandingTablesEnabled,
) : SEAViewModel<StandingTableState, StandingTableEvent, StandingTableAction>(StandingTableState(justCreated = justCreated)) {
    private var countedView = false
    init { if (enabled()) viewModelScope.launch { takeAction(StandingTableAction.Refresh) } }

    override suspend fun handleAction(action: StandingTableAction) {
        if (!enabled()) return
        when (action) {
            StandingTableAction.Refresh -> {
                repository.get(code).fold(
                    onSuccess = { table ->
                        action.updateState { it.copy(table = table, loading = false, error = false) }
                        if (!countedView) {
                            countedView = true
                            KLog.logEvent("standing_table.viewed", "is_owner" to table.isOwner, "live" to (table.live != null))
                        }
                    },
                    onFailure = { action.updateState { it.copy(loading = false, error = true) } },
                )
            }
            StandingTableAction.Open -> if (state.table != null) {
                KLog.logEvent("standing_table.opened", "entry" to "detail")
                sendEvent(StandingTableEvent.Open(code))
            }
            StandingTableAction.Share -> state.table?.let { sendEvent(StandingTableEvent.Share(it)) }
            StandingTableAction.AddCalendar -> state.table?.let { sendEvent(StandingTableEvent.AddCalendar(it)) }
            StandingTableAction.Edit -> if (state.table?.isOwner == true) sendEvent(StandingTableEvent.Edit(code))
            StandingTableAction.RequestDelete -> if (state.table?.isOwner == true) action.updateState { it.copy(confirmingDelete = true) }
            StandingTableAction.CancelDelete -> action.updateState { it.copy(confirmingDelete = false) }
            StandingTableAction.ConfirmDelete -> if (state.table?.isOwner == true && state.confirmingDelete && !state.busy) {
                action.updateState { it.copy(busy = true, error = false) }
                repository.archive(code).fold(
                    onSuccess = {
                        KLog.logEvent("standing_table.archived")
                        action.updateState { it.copy(busy = false, confirmingDelete = false) }
                        sendEvent(StandingTableEvent.Removed)
                    },
                    onFailure = { action.updateState { it.copy(busy = false, error = true) } },
                )
            }
            StandingTableAction.Remove -> if (state.table?.isOwner == false && !state.busy) {
                action.updateState { it.copy(busy = true, error = false) }
                repository.leave(code).fold(
                    onSuccess = { action.updateState { it.copy(busy = false) }; sendEvent(StandingTableEvent.Removed) },
                    onFailure = { action.updateState { it.copy(busy = false, error = true) } },
                )
            }
        }
    }
}

data class StandingTableState(
    val table: StandingTable? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val justCreated: Boolean = false,
    val confirmingDelete: Boolean = false,
    val busy: Boolean = false,
)

sealed interface StandingTableAction {
    data object Refresh : StandingTableAction
    data object Open : StandingTableAction
    data object Share : StandingTableAction
    data object AddCalendar : StandingTableAction
    data object Edit : StandingTableAction
    data object RequestDelete : StandingTableAction
    data object CancelDelete : StandingTableAction
    data object ConfirmDelete : StandingTableAction
    data object Remove : StandingTableAction
}

sealed interface StandingTableEvent {
    data class Open(val code: String) : StandingTableEvent
    data class Edit(val code: String) : StandingTableEvent
    data class Share(val table: StandingTable) : StandingTableEvent
    data class AddCalendar(val table: StandingTable) : StandingTableEvent
    data object Removed : StandingTableEvent
}
