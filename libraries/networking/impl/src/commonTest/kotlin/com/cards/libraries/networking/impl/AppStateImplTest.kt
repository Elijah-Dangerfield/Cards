package com.dangerfield.cards.libraries.networking.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the offline-banner contract:
 *  - initial value is optimistically `false` (online) so cold launches
 *    don't flash a banner before the platform observer's first emission;
 *  - subsequent emissions invert the observer (`observe() == true → isOffline == false`);
 *  - `isBlockActive` is always `false` in the V1 impl — the field is on
 *    the interface for future overlay use but unwired today, and a
 *    regression that lights it up shouldn't ship silently.
 */
class AppStateImplTest : CoroutineTest() {

    @Test
    fun isOffline_initialValueIsFalse_optimisticallyOnline() = runUnitTest {
        val observer = FakeConnectivityObserver()
        val state = AppStateImpl(
            connectivityObserver = observer,
            appScope = AppCoroutineScope(dispatchers),
        )

        assertEquals(false, state.isOffline.value)
    }

    @Test
    fun isOffline_observerEmitsTrue_isOfflineFalse() = runUnitTest {
        val observer = FakeConnectivityObserver()
        val state = AppStateImpl(
            connectivityObserver = observer,
            appScope = AppCoroutineScope(dispatchers),
        )

        state.isOffline.test {
            assertEquals(false, awaitItem())
            observer.emit(online = true)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun isOffline_observerEmitsFalse_isOfflineTrue() = runUnitTest {
        val observer = FakeConnectivityObserver()
        val state = AppStateImpl(
            connectivityObserver = observer,
            appScope = AppCoroutineScope(dispatchers),
        )

        state.isOffline.test {
            assertEquals(false, awaitItem())
            observer.emit(online = false)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun isOffline_followsObserverTransitions() = runUnitTest {
        val observer = FakeConnectivityObserver()
        val state = AppStateImpl(
            connectivityObserver = observer,
            appScope = AppCoroutineScope(dispatchers),
        )

        state.isOffline.test {
            assertEquals(false, awaitItem())
            observer.emit(online = false)
            assertEquals(true, awaitItem())
            observer.emit(online = true)
            assertEquals(false, awaitItem())
            observer.emit(online = false)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun isBlockActive_alwaysFalseInV1() = runUnitTest {
        val observer = FakeConnectivityObserver()
        val state = AppStateImpl(
            connectivityObserver = observer,
            appScope = AppCoroutineScope(dispatchers),
        )

        assertFalse(state.isBlockActive.value)
        observer.emit(online = false)
        assertFalse(state.isBlockActive.value)
        observer.emit(online = true)
        assertFalse(state.isBlockActive.value)
    }

    @Test
    fun isOffline_subscriptionIsEager_followsObserverBeforeFirstCollect() = runUnitTest {
        val observer = FakeConnectivityObserver()
        val state = AppStateImpl(
            connectivityObserver = observer,
            appScope = AppCoroutineScope(dispatchers),
        )

        observer.emit(online = false)

        assertEquals(true, state.isOffline.value)
    }

    private class FakeConnectivityObserver : ConnectivityObserver {
        private val emissions = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 8)

        override fun observe(): Flow<Boolean> = emissions.asSharedFlow()

        suspend fun emit(online: Boolean) {
            emissions.emit(online)
        }
    }
}
