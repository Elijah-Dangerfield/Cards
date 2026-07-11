package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [ConnectivityEdgeDispatcher]: the optimistic initial value is ignored,
 * only the true→false (regained) edge dispatches, and flapping debounces to a
 * single event.
 *
 * Uses [StandardTestDispatcher] so the debounce window can be advanced
 * explicitly — and so distinct connectivity transitions don't get conflated
 * away by the [StateFlow] before the collector observes them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityEdgeDispatcherTest : CoroutineTest() {

    override val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @Test
    fun ignoresInitialValue_noEventOnOnlineColdBoot() = runUnitTest {
        val appState = FakeAppState(isOffline = MutableStateFlow(false))
        val bus = RecordingBus()
        build(appState, bus)

        advanceUntilIdle()

        assertEquals(emptyList<AppEvent>(), bus.events, "an online cold boot must not fire a regain")
    }

    @Test
    fun dispatchesRegained_onOfflineToOnlineEdge() = runUnitTest {
        val appState = FakeAppState(isOffline = MutableStateFlow(true)) // booted offline
        val bus = RecordingBus()
        build(appState, bus)
        advanceUntilIdle()

        appState.isOffline.value = false
        advanceUntilIdle()

        assertEquals(listOf<AppEvent>(AppEvent.ConnectivityRegained), bus.events)
    }

    @Test
    fun goingOffline_doesNotDispatch() = runUnitTest {
        val appState = FakeAppState(isOffline = MutableStateFlow(false))
        val bus = RecordingBus()
        build(appState, bus)
        advanceUntilIdle()

        appState.isOffline.value = true
        advanceUntilIdle()

        assertEquals(emptyList<AppEvent>(), bus.events, "online→offline is not a regain")
    }

    @Test
    fun regain_fires_onlyAfterDebounceWindow() = runUnitTest {
        val appState = FakeAppState(isOffline = MutableStateFlow(true))
        val bus = RecordingBus()
        build(appState, bus)
        advanceUntilIdle()

        appState.isOffline.value = false
        advanceTimeBy(DEBOUNCE_MS - 1)
        assertEquals(emptyList<AppEvent>(), bus.events, "must wait out the debounce window before firing")

        advanceUntilIdle()
        assertEquals(listOf<AppEvent>(AppEvent.ConnectivityRegained), bus.events)
    }

    @Test
    fun flapping_debouncesToSingleRegain() = runUnitTest {
        val appState = FakeAppState(isOffline = MutableStateFlow(true))
        val bus = RecordingBus()
        build(appState, bus)
        advanceUntilIdle()

        // Bounce within the window (each step < DEBOUNCE_MS so the timer keeps
        // resetting), settling online.
        val step = DEBOUNCE_MS / 2
        appState.isOffline.value = false
        advanceTimeBy(step)
        appState.isOffline.value = true
        advanceTimeBy(step)
        appState.isOffline.value = false
        advanceTimeBy(step)
        assertEquals(emptyList<AppEvent>(), bus.events, "still flapping — nothing settled yet")

        advanceUntilIdle()
        assertEquals(listOf<AppEvent>(AppEvent.ConnectivityRegained), bus.events, "flapping collapses to one")
    }

    private fun build(appState: AppState, bus: AppEventBus) = ConnectivityEdgeDispatcher(
        appState = appState,
        appEventBus = bus,
        appScope = AppCoroutineScope(dispatchers),
    )

    private class FakeAppState(
        override val isOffline: MutableStateFlow<Boolean>,
    ) : AppState {
        override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private class RecordingBus : AppEventBus {
        val events = mutableListOf<AppEvent>()
        private val stream = MutableSharedFlow<AppEvent>(replay = 1, extraBufferCapacity = 64)
        override fun dispatch(event: AppEvent) {
            events += event
            stream.tryEmit(event)
        }
        override fun eventStream(): Flow<AppEvent> = stream
        override fun liveEventStream(): Flow<AppEvent> = stream
    }

    private companion object {
        const val DEBOUNCE_MS: Long = 750L
    }
}
