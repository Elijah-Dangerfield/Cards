package com.dangerfield.cards.features.progression.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class RankDetailSheetViewModel(
    authRepository: AuthRepository,
) : SEAViewModel<RankDetailState, RankDetailEvent, RankDetailAction>(
    initialStateArg = RankDetailState(),
) {

    init {
        viewModelScope.launch {
            authRepository.observe().collect { state ->
                val anon = (state as? AuthState.Authenticated)?.isAnonymous ?: true
                takeAction(RankDetailAction.UserChanged(isAnonymous = anon))
            }
        }
    }

    override suspend fun handleAction(action: RankDetailAction) {
        when (action) {
            is RankDetailAction.UserChanged -> action.updateState {
                // V1: rank stays at 0 for anon users (no auth → no MP → no Elo).
                // When the MP gameplay layer lands, this reads from a real
                // rank repo backed by per-hand Elo deltas. The 1200 default
                // is the placeholder a claimed user sees until they've played.
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
