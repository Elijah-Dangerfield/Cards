package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.LifetimeStatsRepository
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
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
    playStyleRepository: PlayStyleRepository,
    xpEventRepository: XpEventRepository,
    achievementRepository: AchievementRepository,
    authRepository: AuthRepository,
    xpBoostRepository: XpBoostRepository,
    private val lifetimeStatsRepository: LifetimeStatsRepository,
) : SEAViewModel<StatsState, StatsEvent, StatsAction>(
    initialStateArg = StatsState(),
) {

    init {
        viewModelScope.launch {
            playStyleRepository.observeOwnStyle().collect { style ->
                takeAction(StatsAction.PlayStyleChanged(style))
            }
        }
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
        // Server-only stat — fetched once on entry. A failure leaves the count
        // null and the tile self-hides; there's no local source to fall back on.
        viewModelScope.launch {
            lifetimeStatsRepository.fetchDistinctOpponents().getOrNull()?.let { count ->
                takeAction(StatsAction.DistinctOpponentsChanged(count))
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
            is StatsAction.PlayStyleChanged -> action.updateState {
                it.copy(playStyle = action.playStyle)
            }
            is StatsAction.DistinctOpponentsChanged -> action.updateState {
                it.copy(distinctOpponentsPlayed = action.count)
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
    /** Expiry of the active XP Boost, or `null` if none. Drives the
     *  countdown badge under the XP hero. */
    val xpBoostExpiresAtEpochMs: Long? = null,
    /** The user's derived play-style; null until the first sync populates it. */
    val playStyle: PlayStyleAxes? = null,
    /** Distinct multiplayer opponents played; null until the server stat lands
     *  (or stays null if the fetch fails — the tile self-hides). */
    val distinctOpponentsPlayed: Long? = null,
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

    data class PlayStyleChanged(val playStyle: PlayStyleAxes?) : StatsAction

    data class DistinctOpponentsChanged(val count: Long) : StatsAction
}
