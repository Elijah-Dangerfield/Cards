package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class HomeViewModel(
    private val userRepository: UserRepository,
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
    private val roomRepository: RoomRepository,
) : SEAViewModel<HomeState, HomeEvent, HomeAction>(
    initialStateArg = HomeState()
) {

    init {
        takeAction(HomeAction.Load)
        takeAction(HomeAction.LoadActiveRooms)
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
            is HomeAction.Refresh -> {
                action.loadUser()
                action.loadActiveRooms()
            }
            is HomeAction.LoadActiveRooms -> action.loadActiveRooms()
            is HomeAction.Forfeit -> action.forfeit(action.code)
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

    private suspend fun HomeAction.loadActiveRooms() {
        val rooms = when (val outcome = roomRepository.getActiveRooms()) {
            is GetActiveRoomsOutcome.Success -> outcome.rooms.map { ActiveRoomSummary(it.code) }
            is GetActiveRoomsOutcome.NotSignedIn,
            is GetActiveRoomsOutcome.NetworkError,
            is GetActiveRoomsOutcome.Unknown -> emptyList()
        }
        updateState { it.copy(activeRooms = rooms) }
    }

    private suspend fun HomeAction.forfeit(code: String) {
        updateState { it.copy(activeRooms = it.activeRooms.filterNot { room -> room.code == code }) }
        when (roomRepository.leaveRoom(code)) {
            is LeaveRoomOutcome.Success,
            is LeaveRoomOutcome.NotFound,
            is LeaveRoomOutcome.NotInRoom -> Unit
            is LeaveRoomOutcome.NetworkError,
            is LeaveRoomOutcome.Unknown -> loadActiveRooms()
        }
    }
}

data class HomeState(
    val userName: String? = null,
    val xp: Long = 0,
    val chips: Long = ChipsRepository.STARTING_GRANT,
    val isAnonymous: Boolean = true,
    val activeRooms: List<ActiveRoomSummary> = emptyList(),
)

data class ActiveRoomSummary(
    val code: String,
)

sealed interface HomeEvent

sealed interface HomeAction {
    data object Load : HomeAction
    data object Refresh : HomeAction
    data object LoadActiveRooms : HomeAction
    data class Forfeit(val code: String) : HomeAction
    data class XpChanged(val totalXp: Long) : HomeAction
    data class ChipsChanged(val balance: Long) : HomeAction
}
