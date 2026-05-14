package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
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
    achievementRepository: AchievementRepository,
) : SEAViewModel<XpDetailState, XpDetailEvent, XpDetailAction>(
    initialStateArg = XpDetailState(),
) {

    init {
        viewModelScope.launch {
            combine(
                progressionRepository.observeProgression(),
                xpEventRepository.observeRecent(RECENT_EVENT_LIMIT),
                achievementRepository.observeProgress(),
            ) { progression, events, achievements ->
                Triple(progression, events, achievements)
            }.collect { (progression, events, achievements) ->
                takeAction(XpDetailAction.DataChanged(progression, events, achievements))
            }
        }
    }

    override suspend fun handleAction(action: XpDetailAction) {
        when (action) {
            is XpDetailAction.DataChanged -> action.updateState {
                it.copy(
                    progression = action.progression,
                    recentEvents = action.events,
                    achievements = action.achievements,
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
    val achievements: AchievementProgress = AchievementProgress.Empty,
)

sealed interface XpDetailEvent

sealed interface XpDetailAction {
    data class DataChanged(
        val progression: Progression,
        val events: List<XpEvent>,
        val achievements: AchievementProgress,
    ) : XpDetailAction
}
