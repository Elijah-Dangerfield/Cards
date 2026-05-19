package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.AppLifecycle
import com.dangerfield.cards.libraries.cards.AppLifecycleObserver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the contract every cleanup listener relies on:
 *  - dispatch(SignedOut) fans out to every listener's onSignedOut.
 *  - Listener exceptions don't poison subsequent listeners — the
 *    dispatcher's Catching{} wrap keeps the loop going.
 *  - dispatch is synchronous on the calling thread (we don't post to
 *    a queue), so the AppEventBus contract caller can reason about
 *    ordering vs. its own work.
 *
 * Lifecycle (foreground/cold-boot dispatch) isn't tested here because
 * it's driven via the AppLifecycle observer and the test would just
 * mirror that wiring. SignedOut is the path we explicitly call via
 * AppEventBus, so that's what's worth pinning.
 */
class AppEventDispatcherTest {

    @Test
    fun signedOut_fansOutToEveryListener() {
        val a = Recording()
        val b = Recording()
        val dispatcher = AppEventDispatcher(
            listeners = setOf(a, b),
            appLifecycle = NoopLifecycle,
        )

        dispatcher.dispatch(AppEvent.SignedOut)

        assertEquals(listOf("signedOut"), a.calls)
        assertEquals(listOf("signedOut"), b.calls)
    }

    @Test
    fun listenerException_doesNotBlockOtherListeners() {
        val poison = Throwing()
        val healthy = Recording()
        val dispatcher = AppEventDispatcher(
            listeners = setOf(poison, healthy),
            appLifecycle = NoopLifecycle,
        )

        dispatcher.dispatch(AppEvent.SignedOut)

        // The healthy listener still ran — exact ordering across the
        // set isn't part of the contract (Set is unordered), but
        // "every non-throwing listener fires" is.
        assertEquals(listOf("signedOut"), healthy.calls)
    }

    private class Recording : AppEventListener {
        val calls = mutableListOf<String>()
        override fun onColdBoot(event: AppEvent.ColdBoot) { calls += "coldBoot" }
        override fun onWarmBoot(event: AppEvent.WarmBoot) { calls += "warmBoot" }
        override fun onForeground(event: AppEvent.OnForeground) { calls += "foreground" }
        override fun onBackground(event: AppEvent.OnBackground) { calls += "background" }
        override fun onSignedOut(event: AppEvent.SignedOut) { calls += "signedOut" }
    }

    private class Throwing : AppEventListener {
        override fun onSignedOut(event: AppEvent.SignedOut) {
            throw IllegalStateException("simulated cleanup failure")
        }
    }

    private object NoopLifecycle : AppLifecycle {
        override fun addObserver(observer: AppLifecycleObserver) { /* no-op */ }
        override fun removeObserver(observer: AppLifecycleObserver) { /* no-op */ }
    }
}
