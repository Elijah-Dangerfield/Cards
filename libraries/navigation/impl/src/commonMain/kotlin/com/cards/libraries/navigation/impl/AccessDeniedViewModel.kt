package com.dangerfield.cards.libraries.navigation.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.networking.AccessStatusProbe
import com.dangerfield.cards.libraries.networking.BannedState
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Drives the "check again" affordance on the blocking access-denied screen.
 *
 * Recovery used to mean relaunching the app: the ban was a fire-and-forget
 * signal with nothing holding it, so nothing could observe it lifting. Now the
 * probe is the one request the circuit breaker lets through, and
 * [BannedState.denial] going null is the answer — whether it was this check that
 * cleared it or something else in the app.
 */
@Inject
class AccessDeniedViewModel(
    private val bannedState: BannedState,
    private val accessStatusProbe: AccessStatusProbe,
) : SEAViewModel<
    AccessDeniedViewModel.State,
    AccessDeniedViewModel.Event,
    AccessDeniedViewModel.Action,
    >(initialStateArg = State()) {

    data class State(
        val checking: Boolean = false,
        val stillBlocked: Boolean = false,
    )

    sealed interface Event {
        data object AccessRestored : Event
    }

    sealed interface Action {
        data object CheckAgain : Action
    }

    init {
        // Watching the latch rather than only the probe's return: whatever
        // clears the ban, the screen has to come down. Today that's the button,
        // but any allowlisted `/v1/me` that comes back clean does it too.
        viewModelScope.launch {
            bannedState.denial.collect { denial ->
                if (denial == null) sendEvent(Event.AccessRestored)
            }
        }
    }

    override suspend fun handleAction(action: Action) {
        when (action) {
            Action.CheckAgain -> action.checkAgain()
        }
    }

    private suspend fun Action.checkAgain() {
        updateState { it.copy(checking = true, stillBlocked = false) }
        accessStatusProbe.recheck()
        updateState { it.copy(checking = false, stillBlocked = bannedState.isBanned) }
    }
}
