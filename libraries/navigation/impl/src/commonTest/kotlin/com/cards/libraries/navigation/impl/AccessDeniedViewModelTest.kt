package com.dangerfield.cards.libraries.navigation.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.AccessDeniedBus
import com.dangerfield.cards.libraries.networking.AccessStatusProbe
import com.dangerfield.cards.libraries.networking.BannedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ENG-35. Getting off the access-denied screen used to mean killing the app:
 * the ban was a fire-and-forget signal with nothing holding it, so nothing could
 * observe it lifting.
 */
class AccessDeniedViewModelTest : CoroutineTest() {

    @Test
    fun aCleanProbe_popsTheScreen() = runUnitTest {
        val state = FakeBannedState(banned = true)
        val vm = viewModel(state) { state.clearBanned() }

        vm.eventFlow.test {
            vm.takeAction(AccessDeniedViewModel.Action.CheckAgain)
            assertEquals(AccessDeniedViewModel.Event.AccessRestored, awaitItem())
        }
    }

    @Test
    fun aStillBannedProbe_saysSo_andKeepsTheScreenUp() = runUnitTest {
        val state = FakeBannedState(banned = true)
        val vm = viewModel(state) { }

        vm.takeAction(AccessDeniedViewModel.Action.CheckAgain)

        assertFalse(vm.stateFlow.value.checking)
        assertTrue(vm.stateFlow.value.stillBlocked)
    }

    @Test
    fun eachCheckShowsProgress_andDropsTheAnswerFromTheLastOne() = runUnitTest {
        // The button reads `checking` to disable itself, and a second check that
        // still showed the first one's answer would look like it had already
        // failed before it ran.
        val state = FakeBannedState(banned = true)
        var inFlight = CompletableDeferred<Unit>()
        val vm = viewModel(state) { inFlight.await() }

        vm.stateFlow.test {
            assertEquals(AccessDeniedViewModel.State(), awaitItem())

            vm.takeAction(AccessDeniedViewModel.Action.CheckAgain)
            assertEquals(AccessDeniedViewModel.State(checking = true), awaitItem())
            inFlight.complete(Unit)
            assertEquals(AccessDeniedViewModel.State(stillBlocked = true), awaitItem())

            inFlight = CompletableDeferred()
            vm.takeAction(AccessDeniedViewModel.Action.CheckAgain)
            assertEquals(AccessDeniedViewModel.State(checking = true), awaitItem())
            inFlight.complete(Unit)
            assertEquals(AccessDeniedViewModel.State(stillBlocked = true), awaitItem())
        }
    }

    private fun viewModel(state: BannedState, probe: suspend () -> Unit) = AccessDeniedViewModel(
        state,
        object : AccessStatusProbe {
            override suspend fun recheck() = probe()
        },
    )

    private class FakeBannedState(banned: Boolean) : BannedState {
        private val _denial = MutableStateFlow(
            if (banned) AccessDeniedBus.Denial(reason = "banned", until = null, appealUrl = null) else null,
        )
        override val denial: StateFlow<AccessDeniedBus.Denial?> = _denial
        override val isBanned: Boolean get() = _denial.value != null
        override fun setBanned(denial: AccessDeniedBus.Denial) {
            _denial.value = denial
        }
        override fun clearBanned() {
            _denial.value = null
        }
    }
}
