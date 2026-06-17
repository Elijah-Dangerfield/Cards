package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpEventRepository
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class StatsViewModel(
    progressionRepository: ProgressionRepository,
    xpEventRepository: XpEventRepository,
    achievementRepository: AchievementRepository,
    authRepository: AuthRepository,
    xpBoostRepository: XpBoostRepository,
) : SEAViewModel<StatsState, StatsEvent, StatsAction>(
    initialStateArg = StatsState(),
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
                takeAction(StatsAction.DataChanged(progression, events, achievements))
            }
        }
        viewModelScope.launch {
            authRepository.observe().collect { auth ->
                val isAnonymous = (auth as? AuthState.Authenticated)?.isAnonymous ?: false
                takeAction(StatsAction.AuthChanged(isAnonymous))
            }
        }
        viewModelScope.launch {
            xpBoostRepository.observe().collect { boost ->
                takeAction(StatsAction.BoostChanged(boost.expiresAtEpochMs))
            }
        }
    }

    override suspend fun handleAction(action: StatsAction) {
        when (action) {
            is StatsAction.DataChanged -> action.updateState {
                it.copy(
                    progression = action.progression,
                    recentEvents = action.events,
                    achievements = action.achievements,
                    isLoading = false,
                )
            }
            is StatsAction.AuthChanged -> action.updateState {
                it.copy(isAnonymous = action.isAnonymous)
            }
            is StatsAction.BoostChanged -> action.updateState {
                it.copy(xpBoostExpiresAtEpochMs = action.expiresAtEpochMs)
            }
        }
    }

    private companion object {
        const val RECENT_EVENT_LIMIT = 12
    }
}

data class StatsState(
    val isLoading: Boolean = true,
    val progression: Progression = Progression.Empty,
    val recentEvents: List<XpEvent> = emptyList(),
    val achievements: AchievementProgress = AchievementProgress.Empty,
    val isAnonymous: Boolean = false,
    /** Expiry of the active 2× XP boost, or `null` if none. Drives the
     *  countdown badge under the XP hero. */
    val xpBoostExpiresAtEpochMs: Long? = null,
)

sealed interface StatsEvent

sealed interface StatsAction {
    data class DataChanged(
        val progression: Progression,
        val events: List<XpEvent>,
        val achievements: AchievementProgress,
    ) : StatsAction

    data class AuthChanged(val isAnonymous: Boolean) : StatsAction

    data class BoostChanged(val expiresAtEpochMs: Long?) : StatsAction
}
