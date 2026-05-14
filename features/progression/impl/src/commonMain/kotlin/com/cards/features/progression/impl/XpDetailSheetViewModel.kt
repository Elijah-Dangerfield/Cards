package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpEventRepository
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class XpDetailSheetViewModel(
    progressionRepository: ProgressionRepository,
    xpEventRepository: XpEventRepository,
) : SEAViewModel<XpDetailState, XpDetailEvent, XpDetailAction>(
    initialStateArg = XpDetailState(),
) {

    init {
        viewModelScope.launch {
            combine(
                progressionRepository.observeProgression(),
                xpEventRepository.observeRecent(RECENT_EVENT_LIMIT),
            ) { progression, events -> progression to events }
                .collect { (progression, events) ->
                    takeAction(XpDetailAction.DataChanged(progression, events))
                }
        }
    }

    override suspend fun handleAction(action: XpDetailAction) {
        when (action) {
            is XpDetailAction.DataChanged -> action.updateState {
                it.copy(
                    progression = action.progression,
                    recentEvents = action.events,
                    isLoading = false,
                )
            }
        }
    }

    private companion object {
        const val RECENT_EVENT_LIMIT = 12
    }
}

data class XpDetailState(
    val isLoading: Boolean = true,
    val progression: Progression = Progression.Empty,
    val recentEvents: List<XpEvent> = emptyList(),
)

sealed interface XpDetailEvent

sealed interface XpDetailAction {
    data class DataChanged(
        val progression: Progression,
        val events: List<XpEvent>,
    ) : XpDetailAction
}
