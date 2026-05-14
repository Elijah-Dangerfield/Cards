package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class RankDetailSheetViewModel(
    userRepository: UserRepository,
) : SEAViewModel<RankDetailState, RankDetailEvent, RankDetailAction>(
    initialStateArg = RankDetailState(),
) {

    init {
        viewModelScope.launch {
            userRepository.observeUser().collect { user ->
                val anon = user?.isAnonymous ?: true
                takeAction(RankDetailAction.UserChanged(isAnonymous = anon))
            }
        }
    }

    override suspend fun handleAction(action: RankDetailAction) {
        when (action) {
            is RankDetailAction.UserChanged -> action.updateState {
                // V1: rank stays at 0 for anon users (no auth → no MP → no Elo).
                // Once Phase 3 lands and a claimed user starts multiplayer, this
                // will read from a real rank repo.
                it.copy(isAnonymous = action.isAnonymous, rank = if (action.isAnonymous) 0 else 1200)
            }
        }
    }
}

data class RankDetailState(
    val isAnonymous: Boolean = true,
    val rank: Int = 0,
)

sealed interface RankDetailEvent

sealed interface RankDetailAction {
    data class UserChanged(val isAnonymous: Boolean) : RankDetailAction
}
