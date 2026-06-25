package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.withServerCounters
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class AchievementsViewModel(
    achievementRepository: AchievementRepository,
    playerStatsRepository: PlayerStatsRepository,
) : SEAViewModel<AchievementsState, AchievementsEvent, AchievementsAction>(
    initialStateArg = AchievementsState(),
) {

    init {
        // Earned set comes from the (synced) local achievement progress; the
        // progress-bar counters come from the server's authoritative projection,
        // so they survive reinstall / account switch. `withServerCounters` overlays
        // the server numbers onto the local base (PROG-1 Phase 2b).
        viewModelScope.launch {
            combine(
                achievementRepository.observeProgress(),
                playerStatsRepository.observeStats(),
            ) { progress, stats ->
                stats?.achievementCounters
                    ?.let { progress.withServerCounters(it) }
                    ?: progress
            }.collect { progress ->
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
