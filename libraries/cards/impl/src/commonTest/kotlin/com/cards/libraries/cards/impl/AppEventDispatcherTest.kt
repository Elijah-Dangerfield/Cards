package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.AppLifecycle
import com.dangerfield.cards.libraries.cards.AppLifecycleObserver
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the contract every cleanup listener relies on:
 *  - dispatch(UserChanged) fans out to every listener's onUserChanged.
 *  - Listener exceptions don't poison subsequent listeners — the
 *    dispatcher's Catching{} wrap keeps the loop going.
 *  - dispatch is synchronous on the calling thread (we don't post to
 *    a queue), so the AppEventBus contract caller can reason about
 *    ordering vs. its own work.
 *
 * Lifecycle (foreground/cold-boot dispatch) isn't tested here because
 * it's driven via the AppLifecycle observer and the test would just
 * mirror that wiring. UserChanged is the path we explicitly call via
 * AppEventBus, so that's what's worth pinning.
 */
class AppEventDispatcherTest : CoroutineTest() {

    @Test
    fun userChanged_fansOutToEveryListener() {
        val a = Recording()
        val b = Recording()
        val dispatcher = AppEventDispatcher(
            listeners = setOf(a, b),
            appLifecycle = NoopLifecycle,
        )

        dispatcher.dispatch(AppEvent.UserChanged(previous = "old", current = null))

        assertEquals(listOf("userChanged"), a.calls)
        assertEquals(listOf("userChanged"), b.calls)
    }

    @Test
    fun listenerException_doesNotBlockOtherListeners() {
        val poison = Throwing()
        val healthy = Recording()
        val dispatcher = AppEventDispatcher(
            listeners = setOf(poison, healthy),
            appLifecycle = NoopLifecycle,
        )

        dispatcher.dispatch(AppEvent.UserChanged(previous = "old", current = "new"))

        // The healthy listener still ran — exact ordering across the
        // set isn't part of the contract (Set is unordered), but
        // "every non-throwing listener fires" is.
        assertEquals(listOf("userChanged"), healthy.calls)
    }

    @Test
    fun eventStream_emitsEachDispatchedEvent_inOrder() = runUnitTest {
        val dispatcher = AppEventDispatcher(listeners = emptySet(), appLifecycle = NoopLifecycle)
        val seen = mutableListOf<AppEvent>()
        backgroundScope.launch { dispatcher.eventStream().collect { seen += it } }

        dispatcher.dispatch(AppEvent.UserChanged(previous = "a", current = "b"))
        dispatcher.dispatch(AppEvent.OnForeground(isColdBoot = false))

        assertEquals(
            listOf(
                AppEvent.UserChanged(previous = "a", current = "b"),
                AppEvent.OnForeground(isColdBoot = false),
            ),
            seen,
        )
    }

    @Test
    fun eventStream_and_listeners_seeTheSameEvent() = runUnitTest {
        val listener = Recording()
        val dispatcher = AppEventDispatcher(listeners = setOf(listener), appLifecycle = NoopLifecycle)
        val seen = mutableListOf<AppEvent>()
        backgroundScope.launch { dispatcher.eventStream().collect { seen += it } }

        dispatcher.dispatch(AppEvent.UserChanged(previous = null, current = "u"))

        assertEquals(listOf("userChanged"), listener.calls)
        assertEquals(listOf<AppEvent>(AppEvent.UserChanged(previous = null, current = "u")), seen)
    }

    private class Recording : AppEventListener {
        val calls = mutableListOf<String>()
        override fun onColdBoot(event: AppEvent.ColdBoot) { calls += "coldBoot" }
        override fun onWarmBoot(event: AppEvent.WarmBoot) { calls += "warmBoot" }
        override fun onForeground(event: AppEvent.OnForeground) { calls += "foreground" }
        override fun onBackground(event: AppEvent.OnBackground) { calls += "background" }
        override fun onUserChanged(event: AppEvent.UserChanged) { calls += "userChanged" }
    }

    private class Throwing : AppEventListener {
        override fun onUserChanged(event: AppEvent.UserChanged) {
            throw IllegalStateException("simulated cleanup failure")
        }
    }

    private object NoopLifecycle : AppLifecycle {
        override fun addObserver(observer: AppLifecycleObserver) { /* no-op */ }
        override fun removeObserver(observer: AppLifecycleObserver) { /* no-op */ }
    }
}
