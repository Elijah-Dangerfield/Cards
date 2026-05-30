package com.dangerfield.cards.libraries.navigation.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the deep-link buffering contract. The whole reason
 * `DeepLinkBridgeImpl` exists is that platform deep-link entry points
 * (iOS `.onOpenURL`, Android `onNewIntent`) can fire *before* the App
 * composable mounts and subscribes — a cold launch via a deep link lands
 * the URL in the SharedFlow before there's anything to collect it. The
 * `replay = 1` keeps that URL alive for the late subscriber; an
 * `extraBufferCapacity = 4` + `DROP_OLDEST` keeps a flood of pre-mount
 * URLs from deadlocking the emitter. A regression in either parameter
 * would silently break first-app-launch deep linking.
 */
class DeepLinkBridgeImplTest : CoroutineTest() {

    @Test
    fun emit_beforeSubscribe_isDeliveredToLateSubscriber_viaReplay() = runUnitTest {
        // The cold-launch case: URL arrives before App composable mounts.
        val bridge = DeepLinkBridgeImpl()
        val url = "cards://room/ABC123"

        bridge.emit(url)

        bridge.urls.test {
            assertEquals(url, awaitItem(), "late subscriber must receive the buffered URL")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emit_afterSubscribe_isDeliveredImmediately() = runUnitTest {
        // The warm case: app already mounted, user taps a notification.
        val bridge = DeepLinkBridgeImpl()
        val url = "cards://shop/chips_small"

        bridge.urls.test {
            bridge.emit(url)
            assertEquals(url, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emit_multipleBeforeSubscribe_lateSubscriberSeesMostRecentViaReplay() = runUnitTest {
        // Replay buffer is 1 — if the platform fires two URLs back-to-back
        // before subscription, the late subscriber gets the most recent
        // (the older one is dropped by DROP_OLDEST). Pin so a future
        // change to `replay = N` is deliberate.
        val bridge = DeepLinkBridgeImpl()
        bridge.emit("cards://room/OLD")
        bridge.emit("cards://room/NEW")

        bridge.urls.test {
            assertEquals(
                "cards://room/NEW",
                awaitItem(),
                "with replay=1, late subscriber sees only the most recent URL",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emit_doesNotSuspend_evenWhenNoSubscriber() = runUnitTest {
        // `tryEmit` + `DROP_OLDEST` means the platform's
        // onOpenURL / onNewIntent call site never blocks waiting for a
        // subscriber. Pin by issuing more emits than the buffer can hold
        // (replay 1 + extraBufferCapacity 4 = 5 slots) with zero
        // subscribers and confirming none of them stall the test.
        val bridge = DeepLinkBridgeImpl()
        repeat(10) { i -> bridge.emit("cards://burst/$i") }

        bridge.urls.test {
            assertEquals(
                "cards://burst/9",
                awaitItem(),
                "after overflow the most recent URL still wins the replay slot",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
