package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class AchievementsViewModel(
    achievementRepository: AchievementRepository,
) : SEAViewModel<AchievementsState, AchievementsEvent, AchievementsAction>(
    initialStateArg = AchievementsState(),
) {

    init {
        viewModelScope.launch {
            achievementRepository.observeProgress().collect { progress ->
                takeAction(AchievementsAction.ProgressChanged(progress))
            }
        }
    }

    override suspend fun handleAction(action: AchievementsAction) {
        when (action) {
            is AchievementsAction.ProgressChanged -> action.updateState {
                it.copy(progress = action.progress, isLoading = false)
            }
        }
    }
}

data class AchievementsState(
    val isLoading: Boolean = true,
    val progress: AchievementProgress = AchievementProgress.Empty,
)

sealed interface AchievementsEvent

sealed interface AchievementsAction {
    data class ProgressChanged(val progress: AchievementProgress) : AchievementsAction
}
