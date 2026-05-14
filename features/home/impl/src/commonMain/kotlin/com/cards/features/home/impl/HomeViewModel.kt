package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class HomeViewModel(
    private val userRepository: UserRepository,
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
) : SEAViewModel<HomeState, HomeEvent, HomeAction>(
    initialStateArg = HomeState()
) {

    init {
        takeAction(HomeAction.Load)
        viewModelScope.launch {
            progressionRepository.observeProgression().collect { progression ->
                takeAction(HomeAction.XpChanged(progression.totalXp))
            }
        }
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(HomeAction.ChipsChanged(balance))
            }
        }
    }

    override suspend fun handleAction(action: HomeAction) {
        when (action) {
            is HomeAction.Load -> action.loadUser()
            is HomeAction.Refresh -> action.loadUser()
            is HomeAction.XpChanged -> action.updateState { it.copy(xp = action.totalXp) }
            is HomeAction.ChipsChanged -> action.updateState { it.copy(chips = action.balance) }
        }
    }

    private suspend fun HomeAction.loadUser() {
        val user = userRepository.getUser()
        updateState {
            it.copy(
                userName = user?.name,
                isAnonymous = user?.isAnonymous ?: true,
            )
        }
    }
}

data class HomeState(
    val userName: String? = null,
    val xp: Long = 0,
    val chips: Long = ChipsRepository.STARTING_GRANT,
    val isAnonymous: Boolean = true,
)

sealed interface HomeEvent

sealed interface HomeAction {
    data object Load : HomeAction
    data object Refresh : HomeAction
    data class XpChanged(val totalXp: Long) : HomeAction
    data class ChipsChanged(val balance: Long) : HomeAction
}
