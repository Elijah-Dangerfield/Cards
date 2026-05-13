package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

class PlayBotsViewModel @Inject constructor(
    @Assisted private val difficulty: BotDifficulty,
    @Assisted private val seatCount: Int,
) : SEAViewModel<PlayBotsState, PlayBotsEvent, PlayBotsAction>(initialStateArg = PlayBotsState()) {

    private val humanSeatIndex = 0
    private val botPersonalities = BotPersonality.forDifficulty(difficulty, seatCount - 1)

    private val session = LocalBotsSession(
        difficulty = difficulty,
        humanSeatIndex = humanSeatIndex,
        botPersonalities = botPersonalities,
    )

    init {
        viewModelScope.launch {
            session.state.collect { uiState ->
                takeAction(PlayBotsAction.SessionStateChanged(uiState))
            }
        }
        viewModelScope.launch {
            session.runUntilHumansTurnOrComplete()
        }
    }

    override suspend fun handleAction(action: PlayBotsAction) {
        when (action) {
            is PlayBotsAction.SessionStateChanged -> action.updateState {
                it.copy(table = action.uiState)
            }
            is PlayBotsAction.SubmitIntent -> {
                session.submitHumanIntent(action.intent)
            }
            is PlayBotsAction.ToggleCheatSheet -> action.updateState {
                it.copy(cheatSheetOpen = !it.cheatSheetOpen)
            }
        }
    }
}

data class PlayBotsState(
    val table: TableUiState = TableUiState.Loading,
    val cheatSheetOpen: Boolean = false,
)

sealed class PlayBotsEvent {
    data object Dismissed : PlayBotsEvent()
}

sealed class PlayBotsAction {
    data class SessionStateChanged(val uiState: TableUiState) : PlayBotsAction()
    data class SubmitIntent(val intent: PlayerIntent) : PlayBotsAction()
    data object ToggleCheatSheet : PlayBotsAction()
}
